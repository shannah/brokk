package io.github.jbellis.brokk.context;

import static java.util.Objects.requireNonNullElse;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.FragmentDtos.*;
import io.github.jbellis.brokk.util.HistoryIo.ContentReader;
import io.github.jbellis.brokk.util.HistoryIo.ContentWriter;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/** Mapper to convert between Context domain objects and DTO representations. */
public class DtoMapper {
    private static final Logger logger = LogManager.getLogger(DtoMapper.class);

    private DtoMapper() {
        // Utility class - no instantiation
    }

    public static Context fromCompactDto(
            CompactContextDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCache,
            ContentReader contentReader) {
        var editableFragments = dto.editable().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .toList();

        var readonlyFragments = dto.readonly().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .toList();

        var virtualFragments = dto.virtuals().stream()
                .map(id -> (ContextFragment.VirtualFragment) fragmentCache.get(id))
                .filter(Objects::nonNull)
                .toList();

        var taskHistory = dto.tasks().stream()
                .map(taskRefDto -> {
                    if (taskRefDto.logId() != null) {
                        var logFragment = (ContextFragment.TaskFragment) fragmentCache.get(taskRefDto.logId());
                        if (logFragment != null) {
                            return new TaskEntry(taskRefDto.sequence(), logFragment, null);
                        }
                    } else if (taskRefDto.summaryContentId() != null) {
                        String summary = contentReader.readContent(taskRefDto.summaryContentId());
                        return TaskEntry.fromCompressed(taskRefDto.sequence(), summary);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        var parsedOutputFragment = dto.parsedOutputId() != null
                ? (ContextFragment.TaskFragment) fragmentCache.get(dto.parsedOutputId())
                : null;

        var actionFuture = CompletableFuture.completedFuture(dto.action());
        var ctxId = dto.id() != null ? UUID.fromString(dto.id()) : Context.newContextId();

        return Context.createWithId(
                ctxId,
                mgr,
                editableFragments,
                readonlyFragments,
                virtualFragments,
                taskHistory,
                parsedOutputFragment,
                actionFuture);
    }

    public record GitStateDto(String commitHash, @Nullable String diffContentId) {}

    // Central method for resolving and building fragments, called by HistoryIo within computeIfAbsent
    public static @Nullable ContextFragment resolveAndBuildFragment(
            String idToResolve,
            Map<String, ReferencedFragmentDto> referencedDtos,
            Map<String, VirtualFragmentDto> virtualDtos,
            Map<String, TaskFragmentDto> taskDtos,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            ContentReader contentReader) {
        if (referencedDtos.containsKey(idToResolve)) {
            var dto = referencedDtos.get(idToResolve);
            if (dto instanceof FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildReferencedFragment(castNonNull(dto), mgr, imageBytesMap, contentReader);
        }
        if (virtualDtos.containsKey(idToResolve)) {
            var dto = virtualDtos.get(idToResolve);
            if (dto instanceof FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildVirtualFragment(
                    castNonNull(dto),
                    mgr,
                    imageBytesMap,
                    fragmentCacheForRecursion,
                    referencedDtos,
                    virtualDtos,
                    taskDtos,
                    contentReader);
        }
        if (taskDtos.containsKey(idToResolve)) {
            return _buildTaskFragment(castNonNull(taskDtos.get(idToResolve)), mgr, contentReader);
        }
        logger.error("Fragment DTO not found for ID: {} during resolveAndBuildFragment", idToResolve);
        throw new IllegalStateException("Fragment DTO not found for ID: " + idToResolve);
    }

    private static ContextFragment _buildReferencedFragment(
            ReferencedFragmentDto dto,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
            ContentReader reader) {
        return switch (dto) {
            case ProjectFileDto pfd ->
                ContextFragment.ProjectPathFragment.withId(
                        new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath())), pfd.id(), mgr);
            case ExternalFileDto efd ->
                ContextFragment.ExternalPathFragment.withId(new ExternalFile(Path.of(efd.absPath())), efd.id(), mgr);
            case ImageFileDto ifd -> {
                BrokkFile file = fromImageFileDtoToBrokkFile(ifd, mgr);
                yield ContextFragment.ImageFileFragment.withId(file, ifd.id(), mgr);
            }
            case GitFileFragmentDto gfd ->
                ContextFragment.GitFileFragment.withId(
                        new ProjectFile(Path.of(gfd.repoRoot()), Path.of(gfd.relPath())),
                        gfd.revision(),
                        reader.readContent(gfd.contentId()),
                        gfd.id());
            case FrozenFragmentDto ffd -> {
                yield FrozenFragment.fromDto(
                        ffd.id(),
                        mgr,
                        ContextFragment.FragmentType.valueOf(ffd.originalType()),
                        ffd.description(),
                        ffd.shortDescription(),
                        ffd.isTextFragment() ? reader.readContent(Objects.requireNonNull(ffd.contentId())) : null,
                        imageBytesMap != null ? imageBytesMap.get(ffd.id()) : null,
                        ffd.isTextFragment(),
                        ffd.syntaxStyle(),
                        ffd.files().stream().map(DtoMapper::fromProjectFileDto).collect(Collectors.toSet()),
                        ffd.originalClassName(),
                        ffd.meta(),
                        ffd.repr());
            }
        };
    }

    private static @Nullable ContextFragment.TaskFragment _buildTaskFragment(
            @Nullable TaskFragmentDto dto, IContextManager mgr, ContentReader reader) {
        if (dto == null) return null;
        var messages = dto.messages().stream()
                .map(msgDto -> fromChatMessageDto(msgDto, reader))
                .toList();
        return new ContextFragment.TaskFragment(dto.id(), mgr, messages, dto.sessionName());
    }

    private static @Nullable ContextFragment.VirtualFragment _buildVirtualFragment(
            @Nullable VirtualFragmentDto dto,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            Map<String, ReferencedFragmentDto> allReferencedDtos,
            Map<String, VirtualFragmentDto> allVirtualDtos,
            Map<String, TaskFragmentDto> allTaskDtos,
            ContentReader reader) {
        if (dto == null) return null;
        return switch (dto) {
            case FrozenFragmentDto ffd -> {
                if (isDeprecatedBuildFragment(ffd)) {
                    logger.info("Skipping deprecated BuildFragment during deserialization: {}", ffd.id());
                    yield null;
                }
                yield (FrozenFragment) _buildReferencedFragment(ffd, mgr, imageBytesMap, reader);
            }
            case SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                var messages = searchDto.messages().stream()
                        .map(msgDto -> fromChatMessageDto(msgDto, reader))
                        .toList();
                yield new ContextFragment.SearchFragment(searchDto.id(), mgr, searchDto.query(), messages, sources);
            }
            case TaskFragmentDto taskDto -> _buildTaskFragment(taskDto, mgr, reader);
            case StringFragmentDto stringDto ->
                new ContextFragment.StringFragment(
                        stringDto.id(),
                        mgr,
                        reader.readContent(stringDto.contentId()),
                        stringDto.description(),
                        stringDto.syntaxStyle());
            case SkeletonFragmentDto skeletonDto ->
                new ContextFragment.SkeletonFragment(
                        skeletonDto.id(),
                        mgr,
                        skeletonDto.targetIdentifiers(),
                        ContextFragment.SummaryType.valueOf(skeletonDto.summaryType()));
            case UsageFragmentDto usageDto ->
                new ContextFragment.UsageFragment(
                        usageDto.id(), mgr, usageDto.targetIdentifier(), usageDto.includeTestFiles());
            case PasteTextFragmentDto pasteTextDto ->
                new ContextFragment.PasteTextFragment(
                        pasteTextDto.id(),
                        mgr,
                        reader.readContent(pasteTextDto.contentId()),
                        CompletableFuture.completedFuture(pasteTextDto.description()),
                        CompletableFuture.completedFuture(
                                requireNonNullElse(pasteTextDto.syntaxStyle(), SyntaxConstants.SYNTAX_STYLE_MARKDOWN)));
            case PasteImageFragmentDto pasteImageDto -> {
                try {
                    if (imageBytesMap == null) {
                        logger.error("imageBytesMap is null, cannot load image for {}", pasteImageDto.id());
                        yield null;
                    }
                    byte[] imageBytes = imageBytesMap.get(pasteImageDto.id());
                    if (imageBytes == null) {
                        logger.error("Image bytes not found for fragment: {}", pasteImageDto.id());
                        yield null;
                    }
                    var image = FrozenFragment.bytesToImage(imageBytes);
                    yield new ContextFragment.AnonymousImageFragment(
                            pasteImageDto.id(),
                            mgr,
                            image,
                            CompletableFuture.completedFuture(pasteImageDto.description()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            case StacktraceFragmentDto stDto -> {
                var sources =
                        stDto.sources().stream().map(DtoMapper::fromCodeUnitDto).collect(Collectors.toSet());
                yield new ContextFragment.StacktraceFragment(
                        stDto.id(),
                        mgr,
                        sources,
                        reader.readContent(stDto.originalContentId()),
                        stDto.exception(),
                        reader.readContent(stDto.codeContentId()));
            }
            case CallGraphFragmentDto callGraphDto ->
                new ContextFragment.CallGraphFragment(
                        callGraphDto.id(),
                        mgr,
                        callGraphDto.methodName(),
                        callGraphDto.depth(),
                        callGraphDto.isCalleeGraph());
            case CodeFragmentDto codeDto ->
                new ContextFragment.CodeFragment(codeDto.id(), mgr, fromCodeUnitDto(codeDto.unit()));
            case BuildFragmentDto bfDto ->
                new ContextFragment.BuildFragment(mgr, reader.readContent(bfDto.contentId()));
            case HistoryFragmentDto historyDto -> {
                var historyEntries = historyDto.history().stream()
                        .map(taskEntryDto -> _fromTaskEntryDto(
                                taskEntryDto,
                                mgr,
                                fragmentCacheForRecursion,
                                allReferencedDtos,
                                allVirtualDtos,
                                allTaskDtos,
                                reader))
                        .toList();
                yield new ContextFragment.HistoryFragment(historyDto.id(), mgr, historyEntries);
            }
        };
    }

    private static FrozenFragmentDto toFrozenFragmentDto(FrozenFragment ff, ContentWriter writer) {
        try {
            String contentId = null;
            if (ff.isText()) {
                String singleFile = ff.files().size() == 1
                        ? ff.files().stream()
                                .findFirst()
                                .map(pf -> pf.getRelPath().toString())
                                .orElse(null)
                        : null;
                contentId = writer.writeContent(ff.text(), singleFile);
            }
            var filesDto = ff.files().stream().map(DtoMapper::toProjectFileDto).collect(Collectors.toSet());
            return new FrozenFragmentDto(
                    ff.id(),
                    ff.getType().name(),
                    ff.description(),
                    ff.shortDescription(),
                    contentId,
                    ff.isText(),
                    ff.syntaxStyle(),
                    filesDto,
                    ff.originalClassName(),
                    ff.meta(),
                    ff.repr());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize FrozenFragment to DTO: " + ff.id(), e);
        }
    }

    public static ReferencedFragmentDto toReferencedFragmentDto(ContextFragment fragment, ContentWriter writer) {
        if (fragment instanceof FrozenFragment ff) {
            return toFrozenFragmentDto(ff, writer);
        }
        return switch (fragment) {
            case ContextFragment.ProjectPathFragment pf -> toProjectFileDto(pf);
            case ContextFragment.GitFileFragment gf -> {
                var file = gf.file();
                var fileKey =
                        file.getRoot().toString() + ":" + file.getRelPath().toString();
                String contentId = writer.writeContent(gf.content(), fileKey);
                yield new GitFileFragmentDto(
                        gf.id(), file.getRoot().toString(), file.getRelPath().toString(), gf.revision(), contentId);
            }
            case ContextFragment.ExternalPathFragment ef ->
                new ExternalFileDto(ef.id(), ef.file().getPath().toString());
            case ContextFragment.ImageFileFragment imf -> {
                var file = imf.file();
                String absPath = file.absPath().toString();
                String fileName = file.getFileName().toLowerCase(java.util.Locale.ROOT);
                String mediaType = null;
                if (fileName.endsWith(".png")) mediaType = "image/png";
                else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) mediaType = "image/jpeg";
                else if (fileName.endsWith(".gif")) mediaType = "image/gif";
                yield new ImageFileDto(imf.id(), absPath, mediaType);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported fragment type for referenced DTO conversion: " + fragment.getClass());
        };
    }

    private static BrokkFile fromImageFileDtoToBrokkFile(ImageFileDto ifd, IContextManager mgr) {
        Path path = Path.of(ifd.absPath());
        Path projectRoot = mgr.getProject().getRoot();
        if (path.startsWith(projectRoot)) {
            try {
                Path relPath = projectRoot.relativize(path);
                return new ProjectFile(projectRoot, relPath);
            } catch (IllegalArgumentException e) {
                return new ExternalFile(path);
            }
        }
        return new ExternalFile(path);
    }

    private static ProjectFileDto toProjectFileDto(ContextFragment.ProjectPathFragment fragment) {
        var file = fragment.file();
        return new ProjectFileDto(
                fragment.id(), file.getRoot().toString(), file.getRelPath().toString());
    }

    private static ProjectFileDto toProjectFileDto(ProjectFile pf) {
        return new ProjectFileDto("0", pf.getRoot().toString(), pf.getRelPath().toString());
    }

    public static VirtualFragmentDto toVirtualFragmentDto(
            ContextFragment.VirtualFragment fragment, ContentWriter writer) {
        if (fragment instanceof FrozenFragment ff) {
            return toFrozenFragmentDto(ff, writer);
        }

        return switch (fragment) {
            case ContextFragment.SearchFragment searchFragment -> {
                var sourcesDto = searchFragment.sources().stream()
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                var messagesDto = searchFragment.messages().stream()
                        .map(m -> toChatMessageDto(m, writer))
                        .toList();
                yield new SearchFragmentDto(
                        searchFragment.id(), searchFragment.description(), "", sourcesDto, messagesDto);
            }
            case ContextFragment.TaskFragment tf -> toTaskFragmentDto(tf, writer);
            case ContextFragment.StringFragment sf ->
                new StringFragmentDto(
                        sf.id(), writer.writeContent(sf.text(), null), sf.description(), sf.syntaxStyle());
            case ContextFragment.SkeletonFragment skf ->
                new SkeletonFragmentDto(
                        skf.id(),
                        skf.getTargetIdentifiers(),
                        skf.getSummaryType().name());
            case ContextFragment.UsageFragment uf ->
                new UsageFragmentDto(uf.id(), uf.targetIdentifier(), uf.includeTestFiles());
            case ContextFragment.PasteTextFragment ptf -> {
                String description = getFutureDescription(ptf.getDescriptionFuture(), "Paste of ");
                String contentId = writer.writeContent(ptf.text(), null);
                String syntaxStyle = getFutureSyntaxStyle(ptf.getSyntaxStyleFuture());
                yield new PasteTextFragmentDto(ptf.id(), contentId, description, syntaxStyle);
            }
            case ContextFragment.AnonymousImageFragment aif -> {
                String description = getFutureDescription(aif.descriptionFuture, "Paste of ");
                yield new PasteImageFragmentDto(aif.id(), description);
            }
            case ContextFragment.StacktraceFragment stf -> {
                var sourcesDto =
                        stf.sources().stream().map(DtoMapper::toCodeUnitDto).collect(Collectors.toSet());
                String originalContentId = writer.writeContent(stf.getOriginal(), null);
                String codeContentId = writer.writeContent(stf.getCode(), null);
                yield new StacktraceFragmentDto(
                        stf.id(), sourcesDto, originalContentId, stf.getException(), codeContentId);
            }
            case ContextFragment.CallGraphFragment cgf ->
                new CallGraphFragmentDto(cgf.id(), cgf.getMethodName(), cgf.getDepth(), cgf.isCalleeGraph());
            case ContextFragment.CodeFragment cf -> new CodeFragmentDto(cf.id(), toCodeUnitDto(cf.getCodeUnit()));
            case ContextFragment.BuildFragment bf ->
                new BuildFragmentDto(bf.id(), writer.writeContent(bf.text(), null));
            case ContextFragment.HistoryFragment hf -> {
                var historyDto = hf.entries().stream()
                        .map(te -> toTaskEntryDto(te, writer))
                        .toList();
                yield new HistoryFragmentDto(hf.id(), historyDto);
            }
            default ->
                throw new IllegalArgumentException("Unsupported VirtualFragment type for DTO conversion: "
                        + fragment.getClass().getName());
        };
    }

    private static String getFutureDescription(Future<String> future, String prefix) {
        String description;
        try {
            String fullDescription = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            description =
                    fullDescription.startsWith(prefix) ? fullDescription.substring(prefix.length()) : fullDescription;
        } catch (Exception e) {
            description = "(Error getting paste description: " + e.getMessage() + ")";
        }
        return description;
    }

    private static String getFutureSyntaxStyle(Future<String> future) {
        try {
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN; // Fallback
        }
    }

    private static TaskEntryDto toTaskEntryDto(TaskEntry entry, ContentWriter writer) {
        TaskFragmentDto logDto = null;
        if (entry.log() != null) {
            logDto = toTaskFragmentDto(entry.log(), writer);
        }
        String summaryContentId = null;
        if (entry.summary() != null) {
            summaryContentId = writer.writeContent(entry.summary(), null);
        }
        return new TaskEntryDto(entry.sequence(), logDto, summaryContentId);
    }

    public static TaskFragmentDto toTaskFragmentDto(ContextFragment.TaskFragment fragment, ContentWriter writer) {
        var messagesDto = fragment.messages().stream()
                .map(m -> toChatMessageDto(m, writer))
                .toList();
        return new TaskFragmentDto(fragment.id(), messagesDto, fragment.description());
    }

    private static ChatMessageDto toChatMessageDto(ChatMessage message, ContentWriter writer) {
        String contentId = writer.writeContent(Messages.getRepr(message), null);
        return new ChatMessageDto(message.type().name().toLowerCase(java.util.Locale.ROOT), contentId);
    }

    private static ProjectFile fromProjectFileDto(ProjectFileDto dto) {
        return new ProjectFile(Path.of(dto.repoRoot()), Path.of(dto.relPath()));
    }

    private static ChatMessage fromChatMessageDto(ChatMessageDto dto, ContentReader reader) {
        String content = reader.readContent(dto.contentId());
        return switch (dto.role().toLowerCase(java.util.Locale.ROOT)) {
            case "user" -> dev.langchain4j.data.message.UserMessage.from(content);
            case "ai" -> dev.langchain4j.data.message.AiMessage.from(content);
            case "system", "custom" -> dev.langchain4j.data.message.SystemMessage.from(content);
            default -> throw new IllegalArgumentException("Unsupported message role: " + dto.role());
        };
    }

    private static CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) {
        ProjectFile pf = codeUnit.source();
        ProjectFileDto pfd =
                new ProjectFileDto("0", pf.getRoot().toString(), pf.getRelPath().toString());
        return new CodeUnitDto(pfd, codeUnit.kind().name(), codeUnit.packageName(), codeUnit.shortName());
    }

    private static TaskEntry _fromTaskEntryDto(
            TaskEntryDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            Map<String, ReferencedFragmentDto> allReferencedDtos,
            Map<String, VirtualFragmentDto> allVirtualDtos,
            Map<String, TaskFragmentDto> allTaskDtos,
            ContentReader reader) {
        if (dto.log() != null) {
            var taskFragment = (ContextFragment.TaskFragment) fragmentCacheForRecursion.computeIfAbsent(
                    dto.log().id(),
                    id -> resolveAndBuildFragment(
                            id,
                            allReferencedDtos,
                            allVirtualDtos,
                            allTaskDtos,
                            mgr,
                            null,
                            fragmentCacheForRecursion,
                            reader));
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summaryContentId() != null) {
            String summary = reader.readContent(dto.summaryContentId());
            return TaskEntry.fromCompressed(dto.sequence(), summary);
        }
        throw new IllegalArgumentException("TaskEntryDto has neither log nor summary");
    }

    private static CodeUnit fromCodeUnitDto(CodeUnitDto dto) {
        ProjectFileDto pfd = dto.sourceFile();
        ProjectFile source = new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath()));
        var kind = io.github.jbellis.brokk.analyzer.CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName());
    }

    private static boolean isDeprecatedBuildFragment(FrozenFragmentDto ffd) {
        return "io.github.jbellis.brokk.context.ContextFragment$BuildFragment".equals(ffd.originalClassName())
                || "BUILD_LOG".equals(ffd.originalType());
    }

    /* ───────────── entryInfos mapping ───────────── */

    public static Map<String, EntryInfoDto> toEntryInfosDto(
            Map<UUID, ContextHistory.ContextHistoryEntryInfo> entryInfos) {
        return entryInfos.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> new EntryInfoDto(e.getValue().deletedFiles().stream()
                                .map(DtoMapper::toDeletedFileDto)
                                .toList())));
    }

    public static Map<String, ContextHistory.ContextHistoryEntryInfo> fromEntryInfosDto(
            Map<String, EntryInfoDto> dtoMap) {
        return dtoMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ContextHistory.ContextHistoryEntryInfo(e.getValue().deletedFiles().stream()
                                .map(DtoMapper::fromDeletedFileDto)
                                .toList())));
    }

    private static DeletedFileDto toDeletedFileDto(ContextHistory.DeletedFile df) {
        return new DeletedFileDto(toProjectFileDto(df.file()), df.content(), df.wasTracked());
    }

    private static ContextHistory.DeletedFile fromDeletedFileDto(DeletedFileDto dto) {
        return new ContextHistory.DeletedFile(fromProjectFileDto(dto.file()), dto.content(), dto.wasTracked());
    }
}
