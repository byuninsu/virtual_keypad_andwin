package com.virtualkeypad.android

import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class KeyComboPickerDialog(
    private val context: Context,
    initialDisplayValue: String,
    private val onSave: (String) -> Unit
) {
    private val selectedKeys = LinkedHashSet(parseDisplayValue(initialDisplayValue))
    private lateinit var selectedSummary: TextView
    private val keyButtons = mutableMapOf<String, Button>()

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
        }

        selectedSummary = TextView(context).apply {
            text = buildSummary()
            textSize = 16f
        }
        root.addView(selectedSummary)

        val scroll = ScrollView(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }
        scroll.addView(rows)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                900
            )
        )

        KEYBOARD_ROWS.forEach { row ->
            val horizontalScroll = HorizontalScrollView(context)
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.forEach { key ->
                val button = Button(context).apply {
                    text = key
                    minWidth = 0
                    minimumWidth = 0
                    setOnClickListener {
                        toggleKey(key)
                    }
                }
                keyButtons[key] = button
                updateButtonStyle(key)
                rowLayout.addView(button)
            }

            horizontalScroll.addView(rowLayout)
            rows.addView(horizontalScroll)
        }

        AlertDialog.Builder(context)
            .setTitle("키 조합 선택")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                selectedKeys.clear()
                onSave("")
            }
            .setPositiveButton("Save") { _, _ ->
                onSave(selectedKeys.joinToString(DISPLAY_SEPARATOR))
            }
            .show()
    }

    private fun toggleKey(key: String) {
        if (!selectedKeys.add(key)) {
            selectedKeys.remove(key)
        }
        selectedSummary.text = buildSummary()
        updateButtonStyle(key)
    }

    private fun buildSummary(): String {
        return if (selectedKeys.isEmpty()) {
            "선택된 키 없음"
        } else {
            selectedKeys.joinToString(DISPLAY_SEPARATOR)
        }
    }

    private fun updateButtonStyle(key: String) {
        val button = keyButtons[key] ?: return
        val selected = selectedKeys.contains(key)
        button.alpha = if (selected) 1.0f else 0.55f
    }

    companion object {
        const val DISPLAY_SEPARATOR = " + "

        val KEYBOARD_ROWS = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
            listOf("Z", "X", "C", "V", "B", "N", "M"),
            listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"),
            listOf("Space", "Enter", "Escape", "Tab", "Backspace"),
            listOf("Ctrl", "Shift", "Alt"),
            listOf("NumPad7", "NumPad8", "NumPad9"),
            listOf("NumPad4", "NumPad5", "NumPad6"),
            listOf("NumPad1", "NumPad2", "NumPad3"),
            listOf("NumPad0")
        )

        fun parseDisplayValue(value: String): List<String> {
            return value.split(DISPLAY_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
