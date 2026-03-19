package com.studiomath.drawview.document.tools

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.text.HtmlCompat

object RichTextUtil {

    /** Da Compose a Database (HTML) */
    fun toHtml(annotatedString: AnnotatedString): String {
        val spannable = SpannableString(annotatedString.text)

        annotatedString.spanStyles.forEach { span ->
            val start = span.start
            val end = span.end

            if (span.item.fontWeight == FontWeight.Bold) {
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (span.item.fontStyle == FontStyle.Italic) {
                spannable.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (span.item.color != Color.Unspecified) {
                spannable.setSpan(ForegroundColorSpan(span.item.color.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return HtmlCompat.toHtml(spannable, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
    }

    /** Dal Database (HTML) a Compose */
    fun fromHtml(html: String): AnnotatedString {
        if (html.isEmpty()) return AnnotatedString("")

        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        return buildAnnotatedString {
            append(spanned.toString())

            val spans = spanned.getSpans(0, spanned.length, Any::class.java)
            for (span in spans) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)

                when (span) {
                    is StyleSpan -> {
                        if (span.style == Typeface.BOLD) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                        if (span.style == Typeface.ITALIC) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    }
                    is ForegroundColorSpan -> {
                        addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                    }
                }
            }
        }
    }
}