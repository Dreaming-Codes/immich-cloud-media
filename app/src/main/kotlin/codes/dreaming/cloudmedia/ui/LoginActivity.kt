package codes.dreaming.cloudmedia.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import codes.dreaming.cloudmedia.R
import codes.dreaming.cloudmedia.databinding.ActivityLoginBinding
import codes.dreaming.cloudmedia.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
  private lateinit var binding: ActivityLoginBinding
  private var useApiKey = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ApiClient.initialize(this)

    binding = ActivityLoginBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.toggleAuthMode.setOnClickListener {
      useApiKey = !useApiKey
      updateAuthModeUi()
    }

    binding.loginButton.setOnClickListener { performLogin() }
    binding.logoutButton.setOnClickListener { performLogout() }

    binding.copyEnableCommand.setOnClickListener {
      copyToClipboard(getString(R.string.adb_enable_command))
    }
    binding.copyDisableCommand.setOnClickListener {
      copyToClipboard(getString(R.string.adb_disable_command))
    }

    updateUiState()
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
    binding.errorText.visibility = View.GONE
  }

  private fun performLogin() {
    val serverUrl = binding.serverUrlInput.text?.toString()?.trim() ?: ""
    if (serverUrl.isBlank()) {
      showError(getString(R.string.server_url_required))
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

  private fun copyToClipboard(text: String) {
    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", text))
    Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
  }
}
