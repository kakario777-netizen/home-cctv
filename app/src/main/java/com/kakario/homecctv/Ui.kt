package com.kakario.homecctv

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(24), dp(20), dp(24))
}

fun Activity.setScrollContent(col: LinearLayout) {
    setContentView(ScrollView(this).apply { addView(col) })
}

fun Activity.title(col: LinearLayout, text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 24f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, 0, 0, dp(16))
    col.addView(this)
}

fun Activity.label(col: LinearLayout, text: String, size: Float = 15f): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setPadding(0, dp(6), 0, dp(6))
    col.addView(this)
}

fun Activity.bigButton(col: LinearLayout, text: String, onClick: (View) -> Unit): Button = Button(this).apply {
    this.text = text
    textSize = 17f
    minHeight = dp(56)
    gravity = Gravity.CENTER
    setOnClickListener(onClick)
    col.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    })
}
