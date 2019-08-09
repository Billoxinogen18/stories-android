package com.automattic.photoeditor.util

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.automattic.photoeditor.R

class PermissionUtils {
    interface OnRequestPermissionGrantedCheck {
        fun isPermissionGranted(isGranted: Boolean, permission: String)
    }
    companion object {
        val PERMISSION_REQUEST_CODE = 5200
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val FRAGMENT_DIALOG_TAG = "dialog"

        fun checkPermission(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun requestPermission(activity: Activity, permission: String) {
            ActivityCompat.requestPermissions(activity, arrayOf(permission),
                PERMISSION_REQUEST_CODE
            )
        }

        fun requestPermissions(activity: Activity, permissions: Array<String>) {
            ActivityCompat.requestPermissions(activity, permissions,
                PERMISSION_REQUEST_CODE
            )
        }

        fun requestAllRequiredPermissions(activity: Activity) {
            ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS,
                PERMISSION_REQUEST_CODE
            )
        }

        fun checkAndRequestPermission(activity: Activity, permission: String): Boolean {
            val isGranted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                ActivityCompat.requestPermissions(activity, arrayOf(permission),
                    PERMISSION_REQUEST_CODE
                )
            }
            return isGranted
        }

        fun onRequestPermissionsResult(
            onRequestPermissionChecker: OnRequestPermissionGrantedCheck,
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
        ): Boolean {
            when (requestCode) {
                PERMISSION_REQUEST_CODE -> {
                    onRequestPermissionChecker.isPermissionGranted(
                        grantResults[0] == PackageManager.PERMISSION_GRANTED,
                        permissions[0]
                    )
                    return true
                }
                else -> return false
            }
        }

        fun allRequiredPermissionsGranted(context: Context): Boolean {
            for (permission in REQUIRED_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(
                        context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        }
    }

    class ConfirmationDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            AlertDialog.Builder(activity)
                .setMessage(R.string.request_permissions)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    parentFragment?.requestPermissions(
                        REQUIRED_PERMISSIONS,
                        PERMISSION_REQUEST_CODE
                    )
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    parentFragment?.activity?.finish()
                }
                .create()
    }
}
