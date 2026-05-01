package com.vaults.app.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.vaults.app.R
import com.vaults.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("vaults_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupBackgroundInput()
        setupChangePin()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadSettings() {
        val bgUrl = prefs.getString("background_url", DEFAULT_BG) ?: DEFAULT_BG
        binding.bgInput.setText(bgUrl)
        
        val volume = prefs.getInt("default_volume", 5)
        binding.volumeSlider.progress = volume
        binding.volumeLabel.text = "$volume%"
    }

    private fun setupBackgroundInput() {
        binding.btnSaveBg.setOnClickListener {
            val url = binding.bgInput.text?.toString() ?: DEFAULT_BG
            prefs.edit().putString("background_url", url).apply()
            Toast.makeText(this, "Background saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetBg.setOnClickListener {
            binding.bgInput.setText(DEFAULT_BG)
            prefs.edit().putString("background_url", DEFAULT_BG).apply()
        }
        
        binding.volumeSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.volumeLabel.text = "$progress%"
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val volume = seekBar?.progress ?: 10
                prefs.edit().putInt("default_volume", volume).apply()
                Toast.makeText(this@SettingsActivity, "Volume saved: $volume%", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setupChangePin() {
        binding.btnChangePin.setOnClickListener {
            showChangePinDialog()
        }
    }

    private fun showChangePinDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Enter new 4-digit PIN"

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_pin)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newPin = input.text?.toString() ?: ""
                if (newPin.length == 4 && newPin.all { it.isDigit() }) {
                    prefs.edit().putString("pin", newPin).apply()
                    Toast.makeText(this, "PIN changed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val DEFAULT_BG = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='40' height='40'%3E%3Cpath fill='%23D92B7A' fill-opacity='0.1' d='M0 0h40v40H0z'/%3E%3Cpath fill='%236B2D8C' fill-opacity='0.1' d='M20 20l20 20-20 20-20-20z'/%3E%3C/svg%3E"
    }
}