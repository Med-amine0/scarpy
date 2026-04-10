package com.vaults.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vaults.app.R
import com.vaults.app.databinding.ActivityPinBinding

class PinActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPinBinding
    private val prefs by lazy { getSharedPreferences("vaults_prefs", Context.MODE_PRIVATE) }
    private var enteredPin = StringBuilder()
    private var isSetupMode = false
    private var firstPin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isSetupMode = prefs.getString("pin", null) == null
        updateTitle()
        setupNumpad()
    }

    private fun updateTitle() {
        binding.subtitle.text = when {
            isSetupMode && firstPin == null -> getString(R.string.pin_setup)
            isSetupMode && firstPin != null -> getString(R.string.pin_confirm)
            else -> getString(R.string.pin_subtitle)
        }
    }

    private fun setupNumpad() {
        val buttons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )

        buttons.forEachIndexed { index, btn ->
            btn.setOnClickListener { onDigitClicked(index.toString()) }
        }

        binding.btnBack.setOnClickListener { onBackClicked() }
    }

    private fun onDigitClicked(digit: String) {
        if (enteredPin.length < 4) {
            enteredPin.append(digit)
            updateDots()
        }

        if (enteredPin.length == 4) {
            validatePin()
        }
    }

    private fun onBackClicked() {
        if (enteredPin.isNotEmpty()) {
            enteredPin.deleteCharAt(enteredPin.length - 1)
            updateDots()
        }
    }

    private fun updateDots() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
        dots.forEachIndexed { index, dot ->
            dot.isSelected = index < enteredPin.length
        }
    }

    private fun validatePin() {
        val pin = enteredPin.toString()

        if (isSetupMode) {
            if (firstPin == null) {
                firstPin = pin
                enteredPin.clear()
                updateDots()
                updateTitle()
            } else {
                if (pin == firstPin) {
                    prefs.edit().putString("pin", pin).apply()
                    startMainActivity()
                } else {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                    shakeDots()
                    firstPin = null
                    enteredPin.clear()
                    updateDots()
                    updateTitle()
                }
            }
        } else {
            val savedPin = prefs.getString("pin", null)
            if (pin == savedPin) {
                startMainActivity()
            } else {
                Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
                shakeDots()
                enteredPin.clear()
                updateDots()
            }
        }
    }

    private fun shakeDots() {
        val shake = AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.dotsRow.startAnimation(shake)
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}