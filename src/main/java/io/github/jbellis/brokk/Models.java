package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages dynamically loaded models via LiteLLM.
 */
public final class Models {
    private static final Logger logger = LogManager.getLogger(Models.class);
    public static String[] defaultKeyNames = {"ANTHROPIC_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY", "GEMINI_API_KEY"}; // TODO remove
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    public static final String LITELLM_BASE_URL = "http://localhost:4000"; // Default LiteLLM endpoint

    // Static maps to hold loaded models and their names
    private static final ConcurrentHashMap<String, StreamingChatLanguageModel> loadedModels = new ConcurrentHashMap<>(); // displayName -> model
    private static final ConcurrentHashMap<StreamingChatLanguageModel, String> modelNames = new ConcurrentHashMap<>(); // model -> location
    private static final ConcurrentHashMap<String, String> modelLocations = new ConcurrentHashMap<>(); // displayName -> location

    // Static quick model reference
    private static volatile StreamingChatLanguageModel quickModel = null; // Initialized in init()
    private static volatile SpeechToTextModel sttModel = null; // Initialized in init()

    // langchain4j only supports openai tokenization, this is not very accurate for other providers
    // but doing loc-based estimation based on information in the responses was worse
    private static final OpenAiTokenizer tokenizer = new OpenAiTokenizer("gpt-4o"); // Use a common recent tokenizer

    static final String UNAVAILABLE = "AI is unavailable";

    // Initialize models on class loading
    static {
        init();
    }

    /**
     * Returns the location string (e.g., "openai/gpt-4o") for a given model instance.
     *
     * @param model The model instance.
     * @return The location string, or "unknown" if the model is not recognized.
     */
    public static String nameOf(StreamingChatLanguageModel model) {
        return modelNames.getOrDefault(model, "unknown");
    }

    /**
     * Initializes models by fetching available models from LiteLLM and setting up quickModel and sttModel.
     */
    public static void init() {
        logger.info("Initializing models");
        try {
            Request request = new Request.Builder()
                    .url(LITELLM_BASE_URL + "/model/info")
                    .get()
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                     String errorBody = response.body() != null ? response.body().string() : "(no body)";
                     throw new IOException("Failed to fetch model info: " + response.code() + " " + response.message() + " - " + errorBody);
                }
                // Ensure body is not null before reading
                ResponseBody responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw new IOException("Received empty response body");
                }
                String responseBody = responseBodyObj.string();
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode dataNode = rootNode.path("data");

                if (dataNode.isArray()) {
                    modelLocations.clear(); // Clear previous entries
                    for (JsonNode modelInfo : dataNode) {
                        String location = modelInfo.path("model_name").asText(); // e.g., "openai/gpt-4o"
                        if (location != null && !location.isBlank()) {
                            String displayName = location.substring(location.lastIndexOf('/') + 1); // e.g., "gpt-4o"
                            modelLocations.put(displayName, location);
                            logger.debug("Discovered model: {} -> {}", displayName, location);
                        }
                    }
                    logger.info("Discovered {} models", modelLocations.size());
                } else {
                    logger.error("/model/info did not return a data array. No models discovered.");
                }
            }
        } catch (IOException e) {
            logger.error("Failed to connect to Brokk at {} or parse response: {}. AI models will be unavailable.", LITELLM_BASE_URL, e.getMessage());
            // Ensure maps are clear if connection fails
            modelLocations.clear();
        }

        // Initialize quickModel - attempt to get preferred model, fallback if needed
        String preferredQuickModelLocation = "gemini/gemini-2.0-flash-lite";
        String fallbackQuickModelLocation = modelLocations.values().stream().findFirst().orElseThrow();

        if (modelLocations.containsValue(preferredQuickModelLocation)) {
            quickModel = get(preferredQuickModelLocation, preferredQuickModelLocation);
            logger.info("Initialized quickModel with {}", preferredQuickModelLocation);
        } else {
            logger.warn("Quick model unavailable!? Using first available");
            quickModel = get(fallbackQuickModelLocation, fallbackQuickModelLocation);
        }

        // Initialize sttModel
        String openaiKey = getKey("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            sttModel = new OpenAiWhisperSTT(openaiKey);
            logger.info("Initialized OpenAI Whisper STT model.");
        } else {
            sttModel = new UnavailableSTT();
            logger.warn("OpenAI API key not found. STT model is unavailable.");
        }
    }

    /**
     * Gets a map of available model display names to their full location strings.
     * @return A map where keys are short names (e.g., "gpt-4o") and values are locations (e.g., "openai/gpt-4o").
     */
    public static Map<String, String> getAvailableModels() {
        // Return an immutable copy
        return Map.copyOf(modelLocations);
    }


    /**
     * Retrieves or creates a StreamingChatLanguageModel instance for the given name/location.
     * All models are configured to use the LiteLLM proxy.
     *
     * @param displayName The user-facing name (used for caching, can be same as location).
     * @param location    The model identifier used by LiteLLM (e.g., "openai/gpt-4o", "deepseek/deepseek-chat").
     * @return The StreamingChatLanguageModel instance.
     */
    public static StreamingChatLanguageModel get(String displayName, String location) {
        return loadedModels.computeIfAbsent(displayName, key -> {
            logger.info("Creating new model instance for '{}' at location '{}' via LiteLLM", displayName, location);
            // Use OpenAI client type to connect to LiteLLM proxy
            // API key "litellm" is often used as a placeholder when LiteLLM manages keys
            var model = OpenAiStreamingChatModel.builder()
                    .baseUrl(LITELLM_BASE_URL)
                    .apiKey("litellm") // Placeholder key for LiteLLM
                    .modelName(location) // Pass the full location string to LiteLLM
                    .logRequests(true) // Enable logging for debugging
                    .logResponses(true)
                    .build();
            modelNames.put(model, location); // Store model -> location mapping
            return model;
        });
    }

    public static String getText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> am.text();
            case UserMessage um -> um.singleText();
            default -> throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public static int getApproximateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return tokenizer.encode(text).size();
    }

    /**
     * Returns the statically configured quick model instance.
     * This might be an UnavailableStreamingModel if initialization failed.
     */
    public static StreamingChatLanguageModel quickModel() {
        assert quickModel != null;
        return quickModel;
    }

    /**
     * Returns the statically configured SpeechToTextModel instance.
     * This might be an UnavailableSTT if initialization failed or no key was found.
     */
    public static SpeechToTextModel sttModel() {
        if (sttModel == null) {
            // Should have been initialized in static block, but as a safeguard
            logger.warn("sttModel accessed before initialization, returning UnavailableSTT.");
            return new UnavailableSTT();
        }
       return sttModel;
    }

    /**
     * Retrieves an API key by checking project properties and then environment variables.
     *
     * @param keyName The name of the key (e.g., "OPENAI_API_KEY").
     * @return The key value, or null if not found.
     */
     private static String getKey(String keyName) {
        // First try properties file
        try {
            Path keysPath = Project.getLlmKeysPath();
            if (Files.exists(keysPath)) {
                var properties = new Properties();
                try (var reader = Files.newBufferedReader(keysPath)) {
                    properties.load(reader);
                }
                String propValue = properties.getProperty(keyName);
                if (propValue != null && !propValue.isBlank()) {
                    return propValue.trim();
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading LLM keys: " + e.getMessage());
        }

        // Next try system environment variables
        String envValue = System.getenv(keyName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        // Nothing found
        return null;
    }

    /**
     * Simple interface for speech-to-text operations.
     */
    public interface SpeechToTextModel {
        /**
         * Transcribes the given audio file to text, returning the transcript.
         */
        String transcribe(Path audioFile) throws IOException;
    }

    /**
     * Stubbed / unavailable STT model that always returns a message
     * indicating that STT is disabled.
     */
    public static class UnavailableSTT implements SpeechToTextModel {
        @Override
        public String transcribe(Path audioFile) {
            return "Speech-to-text is unavailable (no OpenAI key).";
        }
    }

    /**
     * Real STT using OpenAI Whisper v1. Uses OkHttp for multipart/form-data upload.
     * We block until the transcription is done. This requires an OpenAI API key.
     */
    public static class OpenAiWhisperSTT implements SpeechToTextModel {
        private static final Logger logger = LogManager.getLogger(OpenAiWhisperSTT.class);

        private final String apiKey;
        private static final String WHISPER_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";
        private final OkHttpClient httpClient;

        public OpenAiWhisperSTT(String apiKey) {
            this.apiKey = apiKey;
            this.httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .build();
        }

        /**
         * Determines the MediaType based on file extension.
         * @param fileName Name of the file
         * @return MediaType for the HTTP request
         */
        private MediaType getMediaTypeFromFileName(String fileName) {
            var extension = fileName.toLowerCase();
            int dotIndex = extension.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = extension.substring(dotIndex + 1);
            }

            return switch (extension) {
                case "mp3", "mpga", "mpeg" -> MediaType.parse("audio/mpeg");
                case "mp4", "m4a" -> MediaType.parse("audio/mp4");
                case "wav" -> MediaType.parse("audio/wav");
                case "webm" -> MediaType.parse("audio/webm");
                default -> MediaType.parse("audio/mpeg"); // fallback
            };
        }

        /**
         * Transcribes the given audio file to text, returning the transcript.
         * Uses OkHttp for multipart/form-data with file, model, and response_format fields.
         */
        @Override
        public String transcribe(Path audioFile) throws IOException {
            logger.info("Beginning transcription for file: {}", audioFile);
            File file = audioFile.toFile();

            // Use getMediaTypeFromFileName if you want to differentiate .wav vs .mp3, etc.
            // For a simpler approach, you could always use MediaType.get("audio/mpeg") or "audio/wav"
            MediaType mediaType = getMediaTypeFromFileName(file.getName());
            RequestBody fileBody = RequestBody.create(file, mediaType);

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(), fileBody)
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(WHISPER_ENDPOINT)
                    .header("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "multipart/form-data")
                    .post(requestBody)
                    .build();

            logger.debug("Sending Whisper STT request to {}", WHISPER_ENDPOINT);

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String bodyStr = response.body() != null ? response.body().string() : "";

                logger.debug("Received response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("Whisper STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new IOException("Whisper STT call failed with status "
                                                  + response.code() + ": " + bodyStr);
                }

                // Parse JSON response
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode node = mapper.readTree(bodyStr);
                    if (node.has("text")) {
                        String transcript = node.get("text").asText().trim();
                        logger.info("Transcription successful, text length={} chars", transcript.length());
                        return transcript;
                    } else {
                        logger.warn("No 'text' field found in Whisper response.");
                        return "No transcription found in response.";
                    }
                } catch (Exception e) {
                    logger.error("Error parsing Whisper JSON response: {}", e.getMessage());
                    throw new IOException("Error parsing Whisper JSON response: " + e.getMessage(), e);
                }
            }
        }
    }
}
