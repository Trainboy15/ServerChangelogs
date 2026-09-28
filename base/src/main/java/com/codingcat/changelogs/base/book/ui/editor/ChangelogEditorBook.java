package com.codingcat.changelogs.base.book.ui.editor;

import com.codingcat.changelogs.base.ServerChangelogs;
import com.codingcat.changelogs.base.data.ChangelogStorage;
import com.codingcat.changelogs.base.book.ui.ChangelogBook;
import com.codingcat.changelogs.platformapi.player.IPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.codingcat.changelogs.base.lang.TranslationSource.translatable;
import static com.codingcat.changelogs.base.lang.TranslationSource.translatableManual;
import static net.kyori.adventure.text.Component.text;

@RequiredArgsConstructor
public class ChangelogEditorBook implements IBook {
    private static final @NotNull Function<EditorSession, String> TITLE_KEY = s -> "book.editor." + s.getId() + ".title";
    private final @NotNull Map<UUID, EditorSession> savedSessions = new ConcurrentHashMap<>();
    private final @Getter String id = "changelog_editor";
    private final @NotNull ChangelogStorage storage;
    private final boolean useFallbackPermissions;

    @Override
    public @NotNull Book build(@NotNull IPlayer p, @NotNull BookSessionManager sessionManager) {
        sessionManager.startSessionIfNoneActive(this, p, () -> {
            EditorSession session = this.savedSessions.remove(p.getUniqueId());
            if (session == null || !session.canBeSaved()) session = new EditorSession.Create(storage.nextUID());
            return session;
        });
        EditorSession session = sessionManager.getSessionData(p, EditorSession.class);
        String sessionTranslation = "Book.editor." + session.getId();
        List<Component> previewLines = session.deserializeLines();
        BiFunction<Component, Integer, Component> lineMapper = (line, idx) -> {
            boolean isEditing = session.getEditingLineIndex() == idx;
            if (isEditing) line = line.decoration(TextDecoration.BOLD, true);
            String prefix = "Book.editor.line_action.";
            NBTCompound payload = new NBTCompound();
            payload.setTag("target_line", new NBTInt(idx));
            Component edit = null;
            if (!isEditing) edit = translatableManual(p, prefix + "format", translatable(prefix + "edit"))
                    .decoration(TextDecoration.BOLD, false)
                    .clickEvent(sessionManager.createSessionBasedClickEvent(this, "start_edit_line", payload));
            Component remove = translatableManual(p, prefix + "format", translatable(prefix + "remove"))
                    .decoration(TextDecoration.BOLD, false)
                    .clickEvent(sessionManager.createSessionBasedClickEvent(this, "remove_line", payload));
            Component finalCmp = line.appendSpace();
            if (!isEditing) finalCmp = finalCmp.append(edit).appendSpace();
            return finalCmp.append(remove);
        };
        ItemBookBody itemBody = new ItemBookBody(ItemStack.builder().type(ItemTypes.WRITABLE_BOOK).build(),
                new PlainMessage(translatableManual(p, sessionTranslation + ".subtitle", text(session.getEntryUID() + 1)), 160),
                false, false, 15, 15);
        PacketEventsFix.fixItemBody(itemBody);
        List<BookBody> body = List.of(itemBody,
                new PlainMessageBookBody(new PlainMessage(translatableManual(p, "Book.editor.hint"), ChangelogBook.LINE_WIDTH)),
                new PlainMessageBookBody(new PlainMessage(!previewLines.isEmpty() ? createLinesComponent(p, lineMapper, previewLines) : translatableManual(p, "Book.editor.empty_preview"), ChangelogBook.LINE_WIDTH))
        );
        if (session.isShowRestoredMessage()) {
            body = new ArrayList<>(body);
            body.add(1, new PlainMessageBookBody(new PlainMessage(translatableManual(p, "Book.editor.restored_session"), 400)));
        }
        List<Input> inputs = List.of(
                new Input("line", new TextInputControl(350, translatableManual(p, "Book.editor.input.contents"),
                        true, session.getCurrentLine(), 5000, null)),
                new Input("author", new TextInputControl(200, translatableManual(p, "Book.editor.input.author"), true, session.getAuthor(), 200, null))
        );
        CommonBookData common = new CommonBookData(
                translatableManual(p, TITLE_KEY.apply(session)),
                null, true, false,
                BookAction.NONE, body, inputs
        );
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(new ActionButton(new CommonButtonData(translatableManual(p, sessionTranslation + ".commit_button"), null, 160), sessionManager.createSessionBasedAction(this, "commit", true)));
        String lineAction = session.getEditingLineIndex() != -1 ? "edit_line" : "add_line";
        buttons.add(new ActionButton(new CommonButtonData(translatableManual(p, "Book.editor.button." + lineAction), null, 100), sessionManager.createSessionBasedAction(this, lineAction, true)));
        ActionButton closeBtn = new ActionButton(new CommonButtonData(translatableManual(p, "Book.editor.button.close"), null, 100), sessionManager.createSessionBasedAction(this, "try_close", true));
        return new MultiActionBook(common, buttons, closeBtn, 3);
    }

    private @NotNull Book buildConfirmCloseBook(@NotNull IPlayer p, @NotNull BookSessionManager sessionManager) {
        EditorSession session = sessionManager.getSessionData(p, EditorSession.class);
        String prefix = "Book.editor.confirm_close.";
        CommonBookData common = BookPackets.createSimpleBook(p, TITLE_KEY.apply(session), prefix + "content", false, false);
        Function<Boolean, Action> closeActionFunc = save -> {
            NBTCompound payload = new NBTCompound();
            payload.setTag("save_session", new NBTByte(save));
            return sessionManager.createSessionBasedAction(this, "close", payload, false);
        };
        ActionButton saveSession = new ActionButton(new CommonButtonData(translatableManual(p, prefix + "button.save_session"), null, 140), closeActionFunc.apply(true));
        ActionButton discardSession = new ActionButton(new CommonButtonData(translatableManual(p, prefix + "button.discard_session"), null, 140), closeActionFunc.apply(false));
        ActionButton cancel = new ActionButton(new CommonButtonData(translatableManual(p, prefix + "button.cancel"), null, 100), sessionManager.createSessionBasedAction(this, "reopen", false));
        return new MultiActionBook(common, List.of(saveSession, discardSession, cancel), null, 3);
    }

    private void actuallyClose(@NotNull IPlayer source, @NotNull BookSessionManager sessionManager) {
        sessionManager.endSession(source);
        BookPackets.clearBook(source, BookPackets.PacketPhase.PLAY);
    }

    @Override
    public void onActionTriggered(@NotNull String action, @Nullable NBTCompound data, @NotNull IPlayer source, @NotNull BookSessionManager sessionManager) {
        EditorSession session = sessionManager.getSessionData(source, EditorSession.class);
        session.setShowRestoredMessage(false);
        if (data != null && data.contains("line")) {
            String rawLine = data.getStringTagValueOrThrow("line");
            String rawAuthor = data.getStringTagValueOrThrow("author");
            session.setCurrentLine(rawLine);
            session.setAuthor(rawAuthor);
        }
        switch (action) {
            case "try_close" -> {
                if (session.canBeSaved()) {
                    Book Book = this.buildConfirmCloseBook(source, sessionManager);
                    BookPackets.showBook(source, Book, BookPackets.PacketPhase.PLAY);
                    return;
                }
                this.actuallyClose(source, sessionManager);
            }
            case "close" -> {
                if (data == null) return;
                boolean saveSession = data.getBooleanOrThrow("save_session");
                if (saveSession) {
                    session.setShowRestoredMessage(true);
                    this.savedSessions.put(source.getUniqueId(), session);
                }
                this.actuallyClose(source, sessionManager);
            }
            case "reopen" -> this.showTo(source, sessionManager, BookPackets.PacketPhase.PLAY);
            case "add_line", "edit_line" -> {
                if (action.equals("edit_line") && (session.getEditingLineIndex() == -1)) return;
                boolean removed = false;
                if (session.getCurrentLine().isBlank()) {
                    if (action.equals("add_line")) {
                        this.showRetry(source, "add_empty_line", session, sessionManager);
                        return;
                    } else {
                        session.getRawLines().remove(session.getEditingLineIndex());
                        removed = true;
                    }
                }
                if (action.equals("edit_line")) {
                    if (!removed) session.getRawLines().set(session.getEditingLineIndex(), session.getCurrentLine());
                    session.setEditingLineIndex(-1);
                } else session.getRawLines().add(session.getCurrentLine());
                session.setCurrentLine("");
                this.showTo(source, sessionManager, BookPackets.PacketPhase.PLAY);
            }
            case "start_edit_line", "remove_line" -> {
                if (data == null) return;
                int lineIdx = data.getNumberTagValueOrThrow("target_line").intValue();
                if (lineIdx < 0 || lineIdx >= session.getRawLines().size()) return;
                if (action.equals("start_edit_line")) {
                    String line = session.getRawLines().get(lineIdx);
                    session.setCurrentLine(line);
                    session.setEditingLineIndex(lineIdx);
                } else {
                    if (session.getEditingLineIndex() == lineIdx) session.setEditingLineIndex(-1);
                    session.getRawLines().remove(lineIdx);
                    session.setCurrentLine("");
                }
                this.showTo(source, sessionManager, BookPackets.PacketPhase.PLAY);
            }
            case "commit" -> {
                if (!permissionCheck(session.getPermission(), source, useFallbackPermissions)) {
                    sessionManager.endSession(source);
                    BookPackets.showSimpleNotice(source, TITLE_KEY.apply(session), "Book.editor.error.no_permission");
                    return;
                }
                if (data == null) return;
                if (session.getRawLines().isEmpty()) {
                    this.showRetry(source, "no_lines", session, sessionManager);
                    return;
                }
                try {
                    session.commit(this.storage);
                } catch (EditorSession.CommitException e) {
                    this.showRetry(source, session.getId() + "." + e.getTranslationKeyPart(), session, sessionManager);
                    return;
                }
                sessionManager.endSession(source);
                BookPackets.showSimpleNotice(source, TITLE_KEY.apply(session), "Book.editor." + session.getId() + ".success");
            }
        }
    }

    @Override
    public void attemptDestroy() throws DestroyRejectedException {
        if (!this.savedSessions.isEmpty()) throw new DestroyRejectedException("saved_sessions");
    }

    private void showRetry(@NotNull IPlayer source, @NotNull String errorPart, @NotNull EditorSession session, @NotNull BookSessionManager sessionManager) {
        BookPackets.showSimpleNotice(source, TITLE_KEY.apply(session), "Book.editor.error." + errorPart, sessionManager.createSessionBasedAction(this, "reopen", false), BookPackets.PacketPhase.PLAY);
    }

    public static boolean permissionCheck(@NotNull String permission, @NotNull IPlayer source, boolean useFallbackPermissions) {
        return useFallbackPermissions ? source.isNativeAdmin().toBooleanOrElse(false) : source.hasPermission(ServerChangelogs.NAMESPACE + "." + permission);
    }
}
