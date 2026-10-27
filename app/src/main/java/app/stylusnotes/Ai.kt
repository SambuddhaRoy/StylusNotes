package app.stylusnotes

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.ByteArrayOutputStream

/** Claude, looking at a picture of the page. Blocking: call off the UI thread. */
object Ai {
    private const val MODEL = "claude-opus-5-5"
    private const val SYSTEM =
        "You help someone working in a handwritten notes app. You get an image of their handwriting, drawings, " +
            "typed text, pictures or equations, and a task. Reply with plain text only (no Markdown), since " +
            "the reply is placed on the page as typed text. When asked for LaTeX, reply with the bare LaTeX expression only."

    enum class Task(val label: String, val prompt: String, val quick: Boolean, val latex: Boolean = false) {
        TRANSCRIBE("Handwriting to text", "Transcribe the handwriting in this image. Keep the line breaks. Reply with the transcription only.", true),
        EQUATION("Handwriting to equation", "Convert the handwritten mathematics in this image into one LaTeX expression, without $ delimiters. Reply with the LaTeX only.", true, latex = true),
        SUMMARIZE("Summarize", "Summarize these notes as a few short bullet points, each starting with •.", false),
        SOLVE("Solve the math", "Solve or check the mathematics in this image. Show the key steps briefly, then the answer.", false),
        EXPLAIN("Explain", "Explain what these notes are about, as you would to a student meeting the topic for the first time. Keep it short.", false),
    }

    class Failure(message: String) : Exception(message)

    fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun run(key: String, instruction: String, image: ByteArray?, quick: Boolean): String {
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        try {
            val blocks = ArrayList<BetaContentBlockParam>()
            if (image != null) blocks += BetaContentBlockParam.ofImage(
                BetaImageBlockParam.builder().source(
                    BetaBase64ImageSource.builder()
                        .data(Base64.encodeToString(image, Base64.NO_WRAP))
                        .mediaType(BetaBase64ImageSource.MediaType.IMAGE_PNG)
                        .build()
                ).build()
            )
            blocks += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(instruction).build())
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(SYSTEM)
                .addUserMessageOfContentBlockParams(blocks)
                // transcription is a simple task; answering questions gets more thought
                .outputConfig(BetaOutputConfig.builder().effort(if (quick) BetaOutputConfig.Effort.LOW else BetaOutputConfig.Effort.MEDIUM).build())
                // if a safety classifier declines, let the server retry on a suitable model instead of failing
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val r = client.beta().messages().create(params)
            if (r.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw Failure("Claude declined this request.")
            val text = r.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            if (text.isEmpty()) throw Failure("Claude didn't return any text.")
            return text
        } catch (e: UnauthorizedException) {
            throw Failure("The API key was rejected. Check it in Settings.")
        } catch (e: RateLimitException) {
            throw Failure("Rate limited. Wait a moment and try again.")
        } catch (e: AnthropicServiceException) {
            throw Failure("Claude API error ${e.statusCode()}: ${e.message}")
        } catch (e: AnthropicIoException) {
            throw Failure("Couldn't reach the Claude API. Check your connection.")
        } finally {
            client.close()
        }
    }

    /** Strips ``` fences and $ delimiters the model might add around LaTeX anyway. */
    fun cleanLatex(s: String) = s.trim().removePrefix("```latex").removePrefix("```").removeSuffix("```").trim()
        .removePrefix("$$").removeSuffix("$$").removePrefix("$").removeSuffix("$").removePrefix("\\[").removeSuffix("\\]").trim()
}
