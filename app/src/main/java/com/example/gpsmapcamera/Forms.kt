package com.example.gpsmapcamera

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Small helpers for the calculator screens: dark background, a top bar with Back,
 * labelled number boxes that remember their last value, and result rows.
 */
class Forms(private val activity: Activity, private val prefsName: String) {

    private val dp = activity.resources.displayMetrics.density
    private val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(8), px(16), px(28))
    }
    private val listeners = ArrayList<() -> Unit>()

    fun px(v: Int) = (v * dp).toInt()

    /** Builds the screen: top bar with title, then a scrolling form. */
    fun screen(title: String): Forms {
        root.setBackgroundColor(Color.parseColor("#111111"))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        bar.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = activity.getString(R.string.back)
            setOnClickListener { activity.finish() }
        }, LinearLayout.LayoutParams(px(56), px(56)))
        bar.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(bar)
        root.addView(ScrollView(activity).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        activity.setContentView(root)
        SystemBars.apply(activity)
        return this
    }

    fun note(text: String, color: String = "#AAAAAA", size: Float = 13f): TextView =
        TextView(activity).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            textSize = size
            setPadding(0, px(6), 0, px(6))
        }.also { content.addView(it) }

    fun section(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFD54F"))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, px(18), 0, px(4))
    }.also { content.addView(it) }

    /** A number box with a label; remembers the last value under [key]. */
    fun number(key: String, label: String, default: Double, decimals: Boolean = true, signed: Boolean = false): EditText {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(4), 0, px(4))
        }
        row.addView(TextView(activity).apply {
            text = label
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val saved = prefs.getString(key, null)
        val box = EditText(activity).apply {
            setText(saved ?: fmt(default))
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimals) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or
                (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222833"))
                cornerRadius = 10 * dp
            }
            setPadding(px(12), px(8), px(12), px(8))
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(key, s.toString()).apply()
                    listeners.forEach { it() }
                }
            })
        }
        row.addView(box, LinearLayout.LayoutParams(px(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(row)
        return box
    }

    /** A plain text box (e.g. customer name); remembers the last value under [key]. */
    fun text(key: String, hint: String): EditText = EditText(activity).apply {
        setText(prefs.getString(key, ""))
        this.hint = hint
        setHintTextColor(Color.parseColor("#77808C"))
        setTextColor(Color.WHITE)
        textSize = 16f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#222833"))
            cornerRadius = 10 * dp
        }
        setPadding(px(12), px(10), px(12), px(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { prefs.edit().putString(key, s.toString()).apply() }
        })
    }.also { add(it, 6) }

    /** A full-width action button. */
    fun button(label: String, color: String = "#1FA855", onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(px(12), px(14), px(12), px(14))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = 26 * dp
        }
        setOnClickListener { onClick() }
    }.also { add(it, 16) }

    /** Choice chips (one selected); remembers the choice under [key]. */
    fun choice(key: String, options: List<String>, default: Int): () -> Int {
        var selected = prefs.getInt(key, default).coerceIn(0, options.lastIndex)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(6), 0, px(6))
        }
        val chips = options.mapIndexed { i, label ->
            TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding(px(6), px(10), px(6), px(10))
            }
        }
        fun paint() = chips.forEachIndexed { i, c ->
            val on = i == selected
            c.setTextColor(if (on) Color.parseColor("#08295F") else Color.WHITE)
            c.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (on) "#FFD54F" else "#222833"))
                cornerRadius = 20 * dp
            }
        }
        chips.forEachIndexed { i, c ->
            c.setOnClickListener {
                selected = i
                prefs.edit().putInt(key, i).apply()
                paint()
                listeners.forEach { it() }
            }
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(6)
            })
        }
        paint()
        content.addView(row)
        return { selected }
    }

    /** A box for results, refreshed by [onChange] callbacks. */
    fun resultBox(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1B2433"))
            cornerRadius = 14 * dp
        }
        setPadding(px(14), px(10), px(14), px(12))
    }.also {
        content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(10)
        })
    }

    /** Adds a "label ..... value" line to a result box. */
    fun line(box: LinearLayout, label: String, value: String, color: String = "#FFFFFF", big: Boolean = false) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(3), 0, px(3))
        }
        row.addView(TextView(activity).apply {
            text = label
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = if (big) 15f else 14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(activity).apply {
            text = value
            setTextColor(Color.parseColor(color))
            textSize = if (big) 18f else 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        })
        box.addView(row)
    }

    fun add(view: View, topMargin: Int = 10) = content.addView(view,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = px(topMargin)
        })

    fun onChange(block: () -> Unit) { listeners.add(block) }

    companion object {
        fun value(box: EditText): Double = box.text.toString().replace(",", "").toDoubleOrNull() ?: 0.0

        fun fmt(v: Double): String =
            if (v == Math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
            else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        /** Indian rupee format: ₹1,23,456 */
        fun rupees(v: Double): String {
            val n = Math.round(v)
            val neg = n < 0
            val s = kotlin.math.abs(n).toString()
            val out = if (s.length <= 3) s else {
                val last3 = s.takeLast(3)
                var rest = s.dropLast(3)
                val parts = ArrayList<String>()
                while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
                if (rest.isNotEmpty()) parts.add(0, rest)
                parts.joinToString(",") + "," + last3
            }
            return (if (neg) "-₹" else "₹") + out
        }
    }
}
