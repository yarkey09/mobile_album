#!/usr/bin/env python3
"""Split approved annotations and export Chinese-CLIP training files."""

from __future__ import annotations

import argparse
import base64
import json
from collections import defaultdict
from pathlib import Path


VALID_GROUPS = {
    "scene_009",
    "scene_020",
    "scene_025",
    "scene_036",
    "scene_046",
}

TEST_GROUPS = {
    "scene_001",
    "scene_011",
    "scene_026",
    "scene_028",
    "scene_034",
    "scene_047",
}

EXPECTED_IMAGE_COUNTS = {
    "train": 60,
    "valid": 8,
    "test": 8,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export approved annotations in Chinese-CLIP TSV/JSONL format."
    )
    parser.add_argument(
        "--annotations",
        type=Path,
        default=Path("dataset/generated/auto_annotations_draft.json"),
        help="Input flat annotation JSON array.",
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=Path("dataset"),
        help="Dataset directory used to resolve annotation image paths.",
    )
    parser.add_argument(
        "--approved-output",
        type=Path,
        default=Path("dataset/generated/annotations_v1.json"),
        help="Output path for approved annotations with assigned splits.",
    )
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("dataset/exports/chinese_clip_v1"),
        help="Output directory for Chinese-CLIP files.",
    )
    return parser.parse_args()


def split_for_group(group_id: str) -> str:
    if group_id in VALID_GROUPS:
        return "valid"
    if group_id in TEST_GROUPS:
        return "test"
    return "train"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def write_jsonl(path: Path, values: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as output:
        for value in values:
            output.write(json.dumps(value, ensure_ascii=False) + "\n")


def load_annotations(path: Path) -> list[dict]:
    annotations = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(annotations, list) or not annotations:
        raise ValueError("Annotation file must contain a non-empty JSON array.")

    required_fields = {
        "image_id",
        "path",
        "caption_id",
        "caption",
        "group_id",
    }
    for index, annotation in enumerate(annotations, start=1):
        missing = required_fields - annotation.keys()
        if missing:
            raise ValueError(
                f"Annotation {index} is missing fields: {sorted(missing)}"
            )
        if not annotation["caption"].strip():
            raise ValueError(f"Annotation {index} has an empty caption.")
    return annotations


def approve_and_split(annotations: list[dict]) -> list[dict]:
    approved = []
    for annotation in annotations:
        record = dict(annotation)
        record["split"] = split_for_group(record["group_id"])
        record["status"] = "approved"
        approved.append(record)
    return approved


def group_by_image(annotations: list[dict]) -> dict[str, list[dict]]:
    records_by_image: dict[str, list[dict]] = defaultdict(list)
    for annotation in annotations:
        records_by_image[annotation["image_id"]].append(annotation)

    for image_id, records in records_by_image.items():
        for field in ("path", "group_id", "split"):
            values = {record[field] for record in records}
            if len(values) != 1:
                raise ValueError(
                    f"Image {image_id} has conflicting {field} values: {values}"
                )
    return records_by_image


def export_dataset(
    annotations: list[dict],
    dataset_dir: Path,
    export_dir: Path,
) -> dict:
    export_dir.mkdir(parents=True, exist_ok=True)
    records_by_image = group_by_image(annotations)
    sorted_images = sorted(
        records_by_image.items(),
        key=lambda item: item[1][0]["path"],
    )

    numeric_image_ids = {
        source_image_id: index
        for index, (source_image_id, _) in enumerate(sorted_images, start=1)
    }
    manifests: dict[str, list[dict]] = {
        "train": [],
        "valid": [],
        "test": [],
    }
    image_rows: dict[str, list[tuple[int, str]]] = {
        "train": [],
        "valid": [],
        "test": [],
    }

    next_text_id = 1
    for source_image_id, records in sorted_images:
        first = records[0]
        split = first["split"]
        image_path = dataset_dir / first["path"]
        if not image_path.is_file():
            raise FileNotFoundError(f"Image does not exist: {image_path}")

        numeric_image_id = numeric_image_ids[source_image_id]
        encoded_image = base64.b64encode(image_path.read_bytes()).decode("ascii")
        image_rows[split].append((numeric_image_id, encoded_image))

        for record in sorted(records, key=lambda item: item["caption_id"]):
            manifests[split].append(
                {
                    "image_id": numeric_image_id,
                    "source_image_id": source_image_id,
                    "text_id": next_text_id,
                    "caption_id": record["caption_id"],
                    "path": record["path"],
                    "text": record["caption"],
                    "group_id": record["group_id"],
                    "split": split,
                    "status": "approved",
                }
            )
            next_text_id += 1

    all_manifest = []
    summary_splits = {}
    for split in ("train", "valid", "test"):
        manifest = manifests[split]
        all_manifest.extend(manifest)
        write_jsonl(export_dir / f"{split}_manifest.jsonl", manifest)
        write_jsonl(
            export_dir / f"{split}_texts.jsonl",
            [
                {
                    "text_id": record["text_id"],
                    "text": record["text"],
                    "image_ids": [record["image_id"]],
                }
                for record in manifest
            ],
        )

        with (export_dir / f"{split}_imgs.tsv").open(
            "w", encoding="utf-8"
        ) as output:
            for image_id, encoded_image in image_rows[split]:
                output.write(f"{image_id}\t{encoded_image}\n")

        image_count = len(image_rows[split])
        expected_count = EXPECTED_IMAGE_COUNTS[split]
        if image_count != expected_count:
            raise ValueError(
                f"{split} contains {image_count} images; expected {expected_count}."
            )
        summary_splits[split] = {
            "images": image_count,
            "captions": len(manifest),
            "groups": len({record["group_id"] for record in manifest}),
        }

    write_jsonl(export_dir / "manifest.jsonl", all_manifest)
    summary = {
        "version": "chinese_clip_v1",
        "split_strategy": "group-aware manual holdout",
        "images": sum(item["images"] for item in summary_splits.values()),
        "captions": sum(item["captions"] for item in summary_splits.values()),
        "groups": len({record["group_id"] for record in all_manifest}),
        "splits": summary_splits,
        "valid_groups": sorted(VALID_GROUPS),
        "test_groups": sorted(TEST_GROUPS),
    }
    write_json(export_dir / "split_summary.json", summary)
    return summary


def main() -> None:
    args = parse_args()
    annotations = load_annotations(args.annotations)
    approved = approve_and_split(annotations)
    write_json(args.approved_output, approved)
    summary = export_dataset(approved, args.dataset_dir, args.export_dir)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
