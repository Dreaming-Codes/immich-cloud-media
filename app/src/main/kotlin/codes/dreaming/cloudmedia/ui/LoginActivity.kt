package codes.dreaming.cloudmedia.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.security.KeyChain
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import codes.dreaming.cloudmedia.R
import codes.dreaming.cloudmedia.databinding.ActivityLoginBinding
import codes.dreaming.cloudmedia.network.ApiClient
import codes.dreaming.cloudmedia.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private var useApiKey = false
    private var pendingAction: (() -> Unit)? = null

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateMediaPermissionUi() }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
        private const val SHIZUKU_PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"

        private val MEDIA_PERMISSIONS = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateShizukuUi() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { updateShizukuUi() }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    pendingAction?.invoke()
                } else {
                    showShizukuStatus(getString(R.string.shizuku_permission_denied), isError = true)
                }
                pendingAction = null
                updateShizukuUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.initialize(this)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleAuthMode.setOnClickListener {
            useApiKey = !useApiKey
            updateAuthModeUi()
        }

        binding.certificateButton.setOnClickListener { selectCertificate() }

        binding.loginButton.setOnClickListener { performLogin() }
        binding.logoutButton.setOnClickListener { performLogout() }

        binding.copyEnableCommand.setOnClickListener {
            copyToClipboard(getString(R.string.adb_enable_command))
        }
        binding.copyDisableCommand.setOnClickListener {
            copyToClipboard(getString(R.string.adb_disable_command))
        }

        // Shizuku setup
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        binding.shizukuEnableButton.setOnClickListener {
            executeWithShizukuPermission { performShizukuEnable() }
        }
        binding.shizukuDisableButton.setOnClickListener {
            executeWithShizukuPermission { performShizukuDisable() }
        }
        binding.shizukuGetButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_PLAY_STORE_URL)))
        }

        binding.mediaPermissionButton.setOnClickListener {
            requestMediaPermissions()
        }

        updateUiState()
        updateShizukuUi()
        updateMediaPermissionUi()
    }

    override fun onResume() {
        super.onResume()
        updateMediaPermissionUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    private fun updateShizukuUi() {
        val available = ShizukuHelper.isShizukuAvailable()

        binding.shizukuEnableButton.isEnabled = available
        binding.shizukuDisableButton.isEnabled = available

        if (!available) {
            showShizukuStatus(getString(R.string.shizuku_not_running), isError = false)
            binding.shizukuGetButton.visibility = View.VISIBLE
        } else {
            showShizukuStatus(getString(R.string.shizuku_ready), isError = false)
            binding.shizukuGetButton.visibility = View.GONE
        }
    }

    private fun showShizukuStatus(message: String, isError: Boolean) {
        binding.shizukuStatusText.text = message
        binding.shizukuStatusText.visibility = View.VISIBLE
        binding.shizukuStatusText.setTextColor(
            getColor(if (isError) com.google.android.material.R.color.design_default_color_error else android.R.color.secondary_text_dark)
        )
    }

    private fun executeWithShizukuPermission(action: () -> Unit) {
        if (!ShizukuHelper.isShizukuAvailable()) {
            updateShizukuUi()
            return
        }
        if (ShizukuHelper.isPermissionGranted()) {
            action()
        } else {
            pendingAction = action
            ShizukuHelper.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    private fun performShizukuEnable() {
        binding.shizukuEnableButton.isEnabled = false
        binding.shizukuDisableButton.isEnabled = false
        lifecycleScope.launch {
            val result = ShizukuHelper.enableProvider()
            binding.shizukuEnableButton.isEnabled = true
            binding.shizukuDisableButton.isEnabled = true
            result.fold(
                onSuccess = {
                    showShizukuStatus(getString(R.string.shizuku_enable_success), isError = false)
                },
                onFailure = { e ->
                    showShizukuStatus(getString(R.string.shizuku_error, e.message), isError = true)
                }
            )
        }
    }

    private fun performShizukuDisable() {
        binding.shizukuEnableButton.isEnabled = false
        binding.shizukuDisableButton.isEnabled = false
        lifecycleScope.launch {
            val result = ShizukuHelper.disableProvider()
            binding.shizukuEnableButton.isEnabled = true
            binding.shizukuDisableButton.isEnabled = true
            result.fold(
                onSuccess = {
                    showShizukuStatus(getString(R.string.shizuku_disable_success), isError = false)
                },
                onFailure = { e ->
                    showShizukuStatus(getString(R.string.shizuku_error, e.message), isError = true)
                }
            )
        }
    }

    private fun updateAuthModeUi() {
        if (useApiKey) {
            binding.credentialsContainer.visibility = View.GONE
            binding.apiKeyContainer.visibility = View.VISIBLE
            binding.toggleAuthMode.text = getString(R.string.use_credentials)
        } else {
            binding.credentialsContainer.visibility = View.VISIBLE
            binding.apiKeyContainer.visibility = View.GONE
            binding.toggleAuthMode.text = getString(R.string.use_api_key)
        }
    }

    private fun updateUiState() {
        if (ApiClient.isLoggedIn) {
            binding.loginForm.visibility = View.GONE
            binding.connectedContainer.visibility = View.VISIBLE
            binding.connectedText.text = getString(R.string.connected_as, ApiClient.accountName ?: ApiClient.serverUrl)
        } else {
            binding.loginForm.visibility = View.VISIBLE
            binding.connectedContainer.visibility = View.GONE
        }
        updateCertificateButton(ApiClient.certificateAlias)
        binding.errorText.visibility = View.GONE
    }

    private fun updateCertificateButton(alias: String?) {
        binding.certificateButton.text =
            if (alias == null) {
                getString(R.string.certificate_button)
            } else {
                getString(R.string.certificate_selected, alias)
            }
    }

    private fun selectCertificate() {
        KeyChain.choosePrivateKeyAlias(
            this,
            { alias ->
                ApiClient.certificateAlias = alias
                runOnUiThread { updateCertificateButton(alias) }
            },
            null,
            null,
            ApiClient.serverUrl,
            -1,
            null,
        )
    }

    private fun performLogin() {
        val serverUrl = binding.serverUrlInput.text?.toString()?.trim() ?: ""
        if (serverUrl.isBlank()) {
            showError(getString(R.string.server_url_required))
            return
        }
        if (ApiClient.certificateAlias != null && ApiClient.getKeyManager() == null) {
            showError(getString(R.string.invalid_certificate))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (useApiKey) {
                    val apiKey = binding.apiKeyInput.text?.toString()?.trim() ?: ""
                    ApiClient.loginWithApiKey(serverUrl, apiKey)
                } else {
                    val email = binding.emailInput.text?.toString()?.trim() ?: ""
                    val password = binding.passwordInput.text?.toString() ?: ""
                    ApiClient.loginWithCredentials(serverUrl, email, password)
                }
            }

            setLoading(false)

            result.fold(
                onSuccess = { updateUiState() },
                onFailure = { e -> showError(getString(R.string.login_error, e.message)) }
            )
        }
    }

    private fun performLogout() {
        ApiClient.logout()
        updateUiState()
    }

    private fun setLoading(loading: Boolean) {
        binding.loginButton.isEnabled = !loading
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.errorText.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun hasMediaPermissions(): Boolean =
        MEDIA_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun updateMediaPermissionUi() {
        val granted = hasMediaPermissions()
        binding.mediaPermissionButton.isEnabled = !granted
        binding.mediaPermissionStatus.text = getString(
            if (granted) R.string.media_permission_granted else R.string.media_permission_not_granted
        )
        binding.mediaPermissionStatus.setTextColor(
            getColor(
                if (granted) android.R.color.holo_green_dark
                else com.google.android.material.R.color.design_default_color_error
            )
        )
    }

    private fun requestMediaPermissions() {
        val needed = MEDIA_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            mediaPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
