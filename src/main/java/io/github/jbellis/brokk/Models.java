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

    // name -> model instance
    private static final ConcurrentHashMap<String, StreamingChatLanguageModel> loadedModels = new ConcurrentHashMap<>();

    // name -> location
    private static final ConcurrentHashMap<String, String> modelLocations = new ConcurrentHashMap<>();

    // "quick" default model instance, set in init()
    private static volatile StreamingChatLanguageModel quickModel = null;

    // STT model
    private static volatile SpeechToTextModel sttModel = null;

    // Simple OpenAI tokenizer for approximate counting
    private static final OpenAiTokenizer tokenizer = new OpenAiTokenizer("gpt-4o");

    static final String UNAVAILABLE = "AI is unavailable";

    // Initialize at class load time
    static {
        init();
    }

    /**
     * Returns the display name for a given model instance
     */
    public static String nameOf(StreamingChatLanguageModel model) {
        return loadedModels.entrySet().stream().filter(e -> e.getValue().equals(model)).findFirst().orElseThrow().getKey();
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
                ResponseBody responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw new IOException("Received empty response body");
                }
                String responseBody = responseBodyObj.string();
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode dataNode = rootNode.path("data");

                if (dataNode.isArray()) {
                    modelLocations.clear(); // Clear previous entries

                    // For each element, parse:
                    //   top-level "model_name" => modelName
                    //   "litellm_params"."model" => modelLocation
                    for (JsonNode modelInfo : dataNode) {
                        String modelName = modelInfo.path("model_name").asText();
                        String modelLocation = modelInfo
                                .path("litellm_params")
                                .path("model")
                                .asText();

                        logger.debug("Parsed from info: modelName={}, modelLocation={}", modelName, modelLocation);

                        if (modelName != null && !modelName.isBlank() &&
                                modelLocation != null && !modelLocation.isBlank())
                        {
                            modelLocations.put(modelName, modelLocation);
                            logger.debug("Discovered model: {} -> {}", modelName, modelLocation);
                        }
                    }

                    logger.info("Discovered {} models", modelLocations.size());
                } else {
                    logger.error("/model/info did not return a data array. No models discovered.");
                }
            }
        } catch (IOException e) {
            logger.error("Failed to connect to Brokk at {} or parse response: {}. AI models will be unavailable.",
                         LITELLM_BASE_URL, e.getMessage());
            modelLocations.clear();
        }

        // Attempt to find a "preferred" quick model
        String preferredQuickModelLocation = "gemini/gemini-2.0-flash-lite";

        // If we see "gemini/gemini-2.0-flash-lite" in the discovered values, use that
        if (modelLocations.containsValue(preferredQuickModelLocation)) {
            var matchingEntry = modelLocations.entrySet().stream()
                    .filter(e -> e.getValue().equals(preferredQuickModelLocation))
                    .findFirst()
                    .orElse(null);

            if (matchingEntry == null) {
                logger.warn("No model name found for location {}", preferredQuickModelLocation);
            } else {
                quickModel = get(matchingEntry.getKey());
                logger.info("Initialized quickModel with {} => {}", matchingEntry.getKey(), preferredQuickModelLocation);
            }
        } else {
            logger.warn("Quick model unavailable!? Using first available");
            var firstEntry = modelLocations.entrySet().iterator().next();
            quickModel = get(firstEntry.getKey());
            logger.info("Using first available model: {} => {}", firstEntry.getKey(), firstEntry.getValue());
        }

        // Initialize STT model
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
     * Retrieves an API key by checking project properties and then environment variables.
     */
    private static String getKey(String keyName) {
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

        String envValue = System.getenv(keyName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return null;
    }

    /**
     * Gets a map of available model *names* to their full location strings.
     * e.g. "deepseek-v3" -> "deepseek/deepseek-chat"
     */
    public static Map<String, String> getAvailableModels() {
        return Map.copyOf(modelLocations);
    }

    /**
     * Retrieves or creates a StreamingChatLanguageModel for the given modelName.
     * The actual location string is looked up internally.
     */
    public static StreamingChatLanguageModel get(String modelName) {
        return loadedModels.computeIfAbsent(modelName, key -> {
            String location = modelLocations.get(modelName);
            logger.info("Creating new model instance for '{}' at location '{}' via LiteLLM", modelName, location);

            // We connect to LiteLLM using an OpenAiStreamingChatModel, specifying baseUrl
            // placeholder, LiteLLM manages actual keys
            return OpenAiStreamingChatModel.builder()
                    .baseUrl(LITELLM_BASE_URL)
                    .apiKey("litellm") // placeholder, LiteLLM manages actual keys
                    .modelName(location)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
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
     */
    public static StreamingChatLanguageModel quickModel() {
        assert quickModel != null;
        return quickModel;
    }

    /**
     * Returns the statically configured SpeechToTextModel instance.
     */
    public static SpeechToTextModel sttModel() {
        if (sttModel == null) {
            logger.warn("sttModel accessed before initialization, returning UnavailableSTT.");
            return new UnavailableSTT();
        }
        return sttModel;
    }

    /**
     * Simple interface for speech-to-text operations.
     */
    public interface SpeechToTextModel {
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
     * Real STT using OpenAI Whisper v1.
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
                default -> MediaType.parse("audio/mpeg");
            };
        }

        @Override
        public String transcribe(Path audioFile) throws IOException {
            logger.info("Beginning transcription for file: {}", audioFile);
            File file = audioFile.toFile();

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
