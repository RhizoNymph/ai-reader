package dev.nymph.aireader.tts.models

import dev.nymph.aireader.model.TtsEngineId

/** One selectable voice inside a multi-speaker model. [speakerId] is the sherpa `sid`. */
data class VoiceInfo(
    val id: String,
    val speakerId: Int,
    val displayName: String,
)

/**
 * Relative paths inside the extracted model directory needed to configure
 * sherpa-onnx. All paths are relative to the model's install dir (the
 * archive's top-level directory is stripped during extraction).
 */
data class ModelFilesOfInterest(
    val modelOnnx: String,
    val tokens: String,
    val voicesBin: String? = null,
    val espeakDataDir: String? = null,
    val lexicons: List<String> = emptyList(),
    val dictDir: String? = null,
) {
    fun allPaths(): List<String> =
        listOfNotNull(modelOnnx, tokens, voicesBin, espeakDataDir, dictDir) + lexicons
}

/** Everything needed to download, install, and configure one voice model. */
data class VoiceModelDescriptor(
    val engineId: TtsEngineId,
    val modelId: String,
    val displayName: String,
    val language: String,
    val downloadUrl: String,
    /** Approximate compressed archive size; used for progress when Content-Length is unknown. */
    val archiveSizeBytes: Long,
    val voices: List<VoiceInfo>,
    val sampleRate: Int,
    val filesOfInterest: ModelFilesOfInterest,
) {
    fun speakerIdFor(voiceId: String): Int? = voices.firstOrNull { it.id == voiceId }?.speakerId
}

/**
 * Hardcoded catalog of supported voice models. URLs and archive layouts were
 * verified against the sherpa-onnx `tts-models` GitHub release and its
 * pretrained-models documentation.
 */
object VoiceModelCatalog {

    private const val RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    /**
     * Kokoro-82M v1.0 multi-lang, int8-quantized (~126 MB archive). 53 speakers;
     * we expose the English subset. Speaker ids follow the documented
     * kokoro-multi-lang-v1_0 id table (0..52, English voices at 0..27).
     */
    val kokoro: VoiceModelDescriptor = VoiceModelDescriptor(
        engineId = TtsEngineId.KOKORO,
        modelId = "kokoro-int8-multi-lang-v1_0",
        displayName = "Kokoro 82M (natural, slower)",
        language = "en",
        downloadUrl = "$RELEASE_BASE/kokoro-int8-multi-lang-v1_0.tar.bz2",
        archiveSizeBytes = 132_120_576, // ~126 MB
        voices = listOf(
            VoiceInfo("af_heart", 3, "Heart (US female)"),
            VoiceInfo("af_bella", 2, "Bella (US female)"),
            VoiceInfo("af_nicole", 6, "Nicole (US female)"),
            VoiceInfo("af_sarah", 9, "Sarah (US female)"),
            VoiceInfo("af_sky", 10, "Sky (US female)"),
            VoiceInfo("am_adam", 11, "Adam (US male)"),
            VoiceInfo("am_michael", 16, "Michael (US male)"),
            VoiceInfo("bf_emma", 21, "Emma (UK female)"),
            VoiceInfo("bf_isabella", 22, "Isabella (UK female)"),
            VoiceInfo("bm_george", 26, "George (UK male)"),
            VoiceInfo("bm_lewis", 27, "Lewis (UK male)"),
        ),
        sampleRate = 24_000,
        filesOfInterest = ModelFilesOfInterest(
            modelOnnx = "model.int8.onnx",
            tokens = "tokens.txt",
            voicesBin = "voices.bin",
            espeakDataDir = "espeak-ng-data",
            lexicons = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt"),
            dictDir = "dict",
        ),
    )

    /**
     * Piper VITS en_US LibriTTS-R medium (~78 MB archive, 904 speakers,
     * 22.05 kHz). We expose a small curated subset of speaker ids.
     */
    val piper: VoiceModelDescriptor = VoiceModelDescriptor(
        engineId = TtsEngineId.PIPER,
        modelId = "vits-piper-en_US-libritts_r-medium",
        displayName = "Piper LibriTTS-R (fast)",
        language = "en-US",
        downloadUrl = "$RELEASE_BASE/vits-piper-en_US-libritts_r-medium.tar.bz2",
        archiveSizeBytes = 82_000_000, // ~78 MB
        voices = listOf(
            VoiceInfo("libritts_r_0", 0, "LibriTTS Voice 1"),
            VoiceInfo("libritts_r_1", 1, "LibriTTS Voice 2"),
            VoiceInfo("libritts_r_2", 2, "LibriTTS Voice 3"),
            VoiceInfo("libritts_r_3", 3, "LibriTTS Voice 4"),
            VoiceInfo("libritts_r_10", 10, "LibriTTS Voice 5"),
            VoiceInfo("libritts_r_20", 20, "LibriTTS Voice 6"),
        ),
        sampleRate = 22_050,
        filesOfInterest = ModelFilesOfInterest(
            modelOnnx = "en_US-libritts_r-medium.onnx",
            tokens = "tokens.txt",
            espeakDataDir = "espeak-ng-data",
        ),
    )

    val all: List<VoiceModelDescriptor> = listOf(kokoro, piper)

    fun forEngine(engineId: TtsEngineId): VoiceModelDescriptor =
        all.first { it.engineId == engineId }
}
