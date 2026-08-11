package com.lanshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.lanshare.app.ui.navigation.LanShareNavGraph
import com.lanshare.app.ui.theme.LanShareTheme
import com.lanshare.app.util.PermissionHelper
import com.lanshare.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 权限处理完毕，启动服务
        viewModel.initialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // 恢复保存的角色
        val savedRole = viewModel.loadSavedRole()
        if (savedRole != MainViewModel.DeviceRole.UNSET) {
            viewModel.setRole(savedRole)
        }

        // 请求运行时权限
        val missingPermissions = PermissionHelper.getMissingPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            viewModel.initialize()
        }

        setContent {
            LanShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LanShareNavGraph(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateNetworkStatus()
    }
}
