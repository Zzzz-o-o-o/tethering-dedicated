package local.tethering.dedicated

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** 画面部品を組み立てる小さな補助（レイアウト XML を使わず、全画面で同じ見た目にする） */
object Ui {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

    /** スクロールする縦並びのページを作り、Activity に設定する */
    fun page(a: Activity): LinearLayout {
        val scroll = ScrollView(a)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(a, 12)
            setPadding(p, p, p, dp(a, 24))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        a.setContentView(scroll)
        return col
    }

    fun card(parent: LinearLayout): LinearLayout {
        val c = parent.context
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(c, 14)
            setPadding(p, dp(c, 12), p, dp(c, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 12).toFloat()
                setColor(c.getColor(R.color.card_background))
                setStroke(dp(c, 1), c.getColor(R.color.card_stroke))
            }
        }
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(c, 10)
        })
        return card
    }

    fun heading(parent: LinearLayout, s: String): TextView =
        text(parent, s, 17f, bold = true).apply { setPadding(0, 0, 0, dp(context, 6)) }

    fun text(parent: LinearLayout, s: String, sizeSp: Float = 14f, colorRes: Int = R.color.text_primary, bold: Boolean = false): TextView {
        val t = TextView(parent.context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(context.getColor(colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }
        parent.addView(t)
        return t
    }

    fun small(parent: LinearLayout, s: String): TextView = text(parent, s, 12.5f, R.color.text_secondary)

    /** 「項目名：値」の行。値の TextView を返すので、あとから更新できる */
    fun kv(parent: LinearLayout, label: String): TextView {
        val c = parent.context
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(c, 3), 0, dp(c, 3))
        }
        val l = TextView(c).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(c.getColor(R.color.text_secondary))
        }
        val v = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(c.getColor(R.color.text_primary))
            gravity = Gravity.END
            setTextIsSelectable(true)
        }
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
        parent.addView(row)
        return v
    }

    fun button(parent: LinearLayout, s: String, onClick: () -> Unit): Button {
        val b = Button(parent.context).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(parent.context, 4)
        })
        return b
    }

    /** 横に並べるボタンの行 */
    fun buttonRow(parent: LinearLayout, vararg items: Pair<String, () -> Unit>): List<Button> {
        val c = parent.context
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val out = items.map { (label, action) ->
            Button(c).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
                row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(c, 4)
        })
        return out
    }

    /** 色付きの小さなラベル（状態表示用） */
    fun chip(parent: LinearLayout, s: String, colorRes: Int): TextView {
        val c = parent.context
        val t = TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.getColor(R.color.chip_text))
            val h = dp(c, 12)
            setPadding(h, dp(c, 4), h, dp(c, 4))
        }
        setChipColor(t, colorRes)
        parent.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(c, 6)
        })
        return t
    }

    fun setChipColor(t: TextView, colorRes: Int) {
        t.background = GradientDrawable().apply {
            cornerRadius = dp(t.context, 14).toFloat()
            setColor(t.context.getColor(colorRes))
        }
    }

    fun mono(parent: LinearLayout, s: String): TextView =
        text(parent, s, 12.5f, R.color.text_primary).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

    /** [signed] なら負の数も入力できる（RSRP のしきい値など） */
    fun numberField(parent: LinearLayout, value: Int, signed: Boolean = false): EditText {
        val e = EditText(parent.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
            setText(value.toString())
            setSelectAllOnFocus(true)
        }
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    fun textField(parent: LinearLayout, value: String): EditText {
        val e = EditText(parent.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(value)
        }
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    fun spacer(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(parent.context), LinearLayout.LayoutParams(1, dp(parent.context, heightDp)))
    }

    fun statusColor(o: Overall): Int = when (o) {
        Overall.OK -> R.color.status_ok
        Overall.WARN -> R.color.status_warn
        Overall.ERROR -> R.color.status_error
        Overall.MAINT -> R.color.status_maint
        Overall.STOPPED, Overall.UNKNOWN -> R.color.status_off
    }
}
