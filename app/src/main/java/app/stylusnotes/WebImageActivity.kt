package app.stylusnotes

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Image search in a browser; long-press any picture to bring it into the note. Returns its URL as "url". */
class WebImageActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface)) }
        val bar = MaterialToolbar(this).apply {
            setNavigationIcon(R.drawable.ic_close); navigationContentDescription = "Close"
            setNavigationOnClickListener { finish() }
        }
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Search images"; setStartIconDrawable(R.drawable.ic_search)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(28f), dp(28f), dp(28f), dp(28f))
        }
        val query = TextInputEditText(field.context).apply {
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(intent.getStringExtra("q") ?: "")
        }
        field.addView(query)
        bar.addView(field, androidx.appcompat.widget.Toolbar.LayoutParams(-1, -2).apply { marginEnd = dpi(16) })
        val progress = LinearProgressIndicator(this).apply { isIndeterminate = false; max = 100 }
        val hint = TextView(this).apply {
            text = "Long-press any picture to insert it"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(themeColor(Attr.onSurfaceVariant)); setPadding(dpi(24), dpi(4), dpi(24), dpi(8))
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) { progress.setProgressCompat(p, true); progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
            }
            setOnLongClickListener { pickUnderFinger() }
        }
        root.addView(bar); root.addView(hint); root.addView(progress)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, s.top, 0, s.bottom); ins
        }
        query.setOnEditorActionListener { _, a, e ->
            if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { search(query.text.toString()); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        search(query.text.toString())
    }

    private fun search(q: String) {
        web.loadUrl("https://duckduckgo.com/?ia=images&iax=images&q=" + Uri.encode(q.ifBlank { "diagram" }))
    }

    private fun pickUnderFinger(): Boolean {
        val r = web.hitTestResult
        when (r.type) {
            WebView.HitTestResult.IMAGE_TYPE -> r.extra?.let { confirm(it) } ?: return false
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = Handler(Looper.getMainLooper()) { m -> (m.data.getString("src") ?: r.extra)?.let { confirm(it) }; true }
                web.requestImageRef(h.obtainMessage())
            }
            else -> return false
        }
        web.hHeavy()
        return true
    }

    private fun confirm(url: String) {
        val img = ImageView(this).apply { adjustViewBounds = true; maxHeight = dpi(320); setPadding(dpi(24), dpi(8), dpi(24), 0) }
        val loading = LoadingIndicator(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; addView(loading); addView(img) }
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Insert this picture?").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Insert") { _, _ -> setResult(RESULT_OK, Intent().putExtra("url", url)); finish() }
            .show()
        bg.execute {
            val bytes = runCatching { Images.fromUrl(url) }.getOrNull()
            runOnUiThread {
                loading.visibility = View.GONE
                if (bytes != null) img.setImageBitmap(Images.decode(bytes)) else { img.visibility = View.GONE; dlg.setMessage("Couldn't load this picture.") }
            }
        }
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
