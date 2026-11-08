#!/usr/bin/env python3
"""Export Chinese-CLIP text and image encoders as FP32 ONNX models."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image
import onnx
import torch


PROJECT_ROOT = Path(__file__).resolve().parents[1]
CHINESE_CLIP_ROOT = PROJECT_ROOT / "Chinese-CLIP"
DEFAULT_DOWNLOAD_ROOT = PROJECT_ROOT / "cache" / "clip"
MODEL_ARCHS = ("ViT-B-16", "ViT-L-14", "ViT-L-14-336", "ViT-H-14", "RN50")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export Chinese-CLIP FP32 ONNX models without modifying the upstream repository."
    )
    parser.add_argument("--model-arch", required=True, choices=MODEL_ARCHS)
    parser.add_argument(
        "--checkpoint",
        type=Path,
        help="Local PyTorch checkpoint. The official checkpoint is downloaded when omitted.",
    )
    parser.add_argument(
        "--download-root",
        type=Path,
        default=DEFAULT_DOWNLOAD_ROOT,
        help=f"Official checkpoint cache directory (default: {DEFAULT_DOWNLOAD_ROOT}).",
    )
    parser.add_argument(
        "--output-prefix",
        required=True,
        type=Path,
        help="Output path prefix, for example models/vit-b-16.",
    )
    parser.add_argument("--convert-text", action="store_true")
    parser.add_argument("--convert-vision", action="store_true")
    parser.add_argument(
        "--context-length",
        type=int,
        default=52,
        help="Padded text length including CLS and SEP tokens (default: 52).",
    )
    parser.add_argument(
        "--opset",
        type=int,
        default=14,
        help="ONNX opset version (default: 14).",
    )
    args = parser.parse_args()

    if not args.convert_text and not args.convert_vision:
        parser.error("enable at least one of --convert-text or --convert-vision")
    if args.checkpoint is not None and not args.checkpoint.is_file():
        parser.error(f"checkpoint does not exist: {args.checkpoint}")
    if not CHINESE_CLIP_ROOT.is_dir():
        parser.error(f"Chinese-CLIP repository does not exist: {CHINESE_CLIP_ROOT}")

    return args


def load_chinese_clip_modules():
    sys.path.insert(0, str(CHINESE_CLIP_ROOT))

    import cn_clip.clip as clip
    from cn_clip.clip.utils import (
        _MODEL_INFO,
        _download,
        create_model,
        image_transform,
    )

    return clip, _MODEL_INFO, _download, create_model, image_transform


def validate_onnx(path: Path) -> None:
    onnx.checker.check_model(str(path))
    print(f"Validated: {path}")


def main() -> None:
    args = parse_args()
    clip, model_info, download, create_model, image_transform = (
        load_chinese_clip_modules()
    )

    checkpoint_path = (
        args.checkpoint
        if args.checkpoint is not None
        else Path(download(args.model_arch, str(args.download_root)))
    )
    print(f"Checkpoint: {checkpoint_path}")

    with checkpoint_path.open("rb") as checkpoint_file:
        checkpoint = torch.load(checkpoint_file, map_location="cpu")

    model = create_model(model_info[args.model_arch]["struct"], checkpoint).float().eval()
    resolution = model_info[args.model_arch]["input_resolution"]
    image = image_transform(resolution)(
        Image.new("RGB", (resolution, resolution))
    ).unsqueeze(0)
    text = clip.tokenize([""], context_length=args.context_length)

    args.output_prefix.parent.mkdir(parents=True, exist_ok=True)

    if args.convert_text:
        text_output = Path(f"{args.output_prefix}.txt.fp32.onnx")
        torch.onnx.export(
            model,
            (None, text),
            text_output,
            input_names=["text"],
            output_names=["unnorm_text_features"],
            export_params=True,
            opset_version=args.opset,
        )
        validate_onnx(text_output)

    if args.convert_vision:
        vision_output = Path(f"{args.output_prefix}.img.fp32.onnx")
        torch.onnx.export(
            model,
            (image, None),
            vision_output,
            input_names=["image"],
            output_names=["unnorm_image_features"],
            export_params=True,
            do_constant_folding=False,
            opset_version=args.opset,
        )
        validate_onnx(vision_output)


if __name__ == "__main__":
    main()
