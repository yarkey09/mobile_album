package com.yarkey.gallery.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object GalleryPermissions {

    fun permissionsForRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun currentAccessMode(context: Context): GalleryAccessMode {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                GalleryAccessMode.FULL
            } else {
                GalleryAccessMode.NONE
            }
        }

        val canReadImages = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES)
        val canReadVideos = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
        val hasSelectedAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        return when {
            canReadImages && canReadVideos -> GalleryAccessMode.FULL
            canReadImages || canReadVideos || hasSelectedAccess -> GalleryAccessMode.LIMITED
            else -> GalleryAccessMode.NONE
        }
    }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
