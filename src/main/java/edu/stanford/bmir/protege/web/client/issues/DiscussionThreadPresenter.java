package edu.stanford.bmir.protege.web.client.issues;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import edu.stanford.bmir.protege.web.client.dispatch.DispatchServiceCallback;
import edu.stanford.bmir.protege.web.client.dispatch.DispatchServiceManager;
import edu.stanford.bmir.protege.web.client.permissions.LoggedInUserProjectPermissionChecker;
import edu.stanford.bmir.protege.web.client.portlet.HasPortletActions;
import edu.stanford.bmir.protege.web.client.portlet.PortletAction;
import edu.stanford.bmir.protege.web.shared.HasDispose;
import edu.stanford.bmir.protege.web.shared.event.PermissionsChangedEvent;
import edu.stanford.bmir.protege.web.shared.issues.Comment;
import edu.stanford.bmir.protege.web.shared.issues.EntityDiscussionThread;
import edu.stanford.bmir.protege.web.shared.issues.GetEntityDiscussionThreadsAction;
import edu.stanford.bmir.protege.web.shared.issues.ThreadId;
import edu.stanford.bmir.protege.web.shared.project.ProjectId;
import org.semanticweb.owlapi.model.OWLEntity;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.stanford.bmir.protege.web.client.ui.library.msgbox.MessageBox.showYesNoConfirmBox;
import static edu.stanford.bmir.protege.web.shared.issues.AddEntityCommentAction.addEntityComment;
import static edu.stanford.bmir.protege.web.shared.issues.CreateEntityDiscussionThreadAction.createEntityDiscussionThread;

/**
 * Matthew Horridge
 * Stanford Center for Biomedical Informatics Research
 * 5 Oct 2016
 */
public class DiscussionThreadPresenter implements HasDispose {

    @Nonnull
    private final EventBus eventBus;

    @Nonnull
    private final DispatchServiceManager dispatch;

    @Nonnull
    private final LoggedInUserProjectPermissionChecker permissionChecker;

    @Nonnull
    private final DiscussionThreadListView view;

    @Nonnull
    private final ProjectId projectId;

    @Nonnull
    private final Provider<DiscussionThreadView> discussionThreadViewProvider;

    @Nonnull
    private final Provider<CommentEditorDialog> commentEditorDialogProvider;

    @Nonnull
    private final CommentViewFactory commentViewFactory;

    private HandlerRegistration handlerRegistration = () -> {
    };

    private final Map<ThreadId, DiscussionThreadView> discussionThreadViewMap = new HashMap<>();

    private final Multimap<ThreadId, Comment> displayedComments = HashMultimap.create();

    private final PortletAction addCommentAction = new PortletAction("Start new thread",
                                                                     (action, event) -> handleCreateThread());

    private Optional<OWLEntity> entity = Optional.empty();


    @Inject
    public DiscussionThreadPresenter(
            @Nonnull EventBus eventBus,
            @Nonnull DispatchServiceManager dispatch,
            @Nonnull LoggedInUserProjectPermissionChecker permissionChecker,
            @Nonnull DiscussionThreadListView view,
            @Nonnull ProjectId projectId,
            @Nonnull Provider<DiscussionThreadView> discussionThreadViewProvider,
            @Nonnull Provider<CommentEditorDialog> commentEditorDialogProvider,
            @Nonnull CommentViewFactory commentViewFactory) {
        this.eventBus = eventBus;
        this.dispatch = dispatch;
        this.permissionChecker = permissionChecker;
        this.view = view;
        this.projectId = projectId;
        this.discussionThreadViewProvider = discussionThreadViewProvider;
        this.commentEditorDialogProvider = commentEditorDialogProvider;
        this.commentViewFactory = commentViewFactory;
    }

    public void installActions(HasPortletActions hasPortletActions) {
        hasPortletActions.addPortletAction(addCommentAction);
    }

    public void start() {
        handlerRegistration = eventBus.addHandler(
                PermissionsChangedEvent.TYPE, event -> updateEnabled());
        updateEnabled();
    }

    public void setEntity(@Nonnull OWLEntity entity) {
        this.entity = Optional.of(entity);
        dispatch.execute(
                new GetEntityDiscussionThreadsAction(projectId, entity),
                result -> displayThreads(result.getThreads())
        );
    }

    @Override
    public void dispose() {
        handlerRegistration.removeHandler();
    }

    public void clear() {
        view.clear();
        displayedComments.clear();
        displayedComments.clear();
    }

    @Nonnull
    public DiscussionThreadListView getView() {
        return view;
    }

    private void updateEnabled() {
        view.setEnabled(false);
        addCommentAction.setEnabled(false);
        permissionChecker.hasCommentPermission(new DispatchServiceCallback<Boolean>() {
            @Override
            public void handleSuccess(Boolean b) {
                view.setEnabled(b);
                addCommentAction.setEnabled(b);
            }
        });
    }


    private void displayThreads(List<EntityDiscussionThread> threads) {
        view.clear();
        discussionThreadViewMap.clear();
        displayedComments.clear();
        for (EntityDiscussionThread thread : threads) {
            DiscussionThreadView threadView = discussionThreadViewProvider.get();
            discussionThreadViewMap.put(thread.getId(), threadView);
            for (Comment comment : thread.getComments()) {
                CommentView commentView = createCommentView(thread.getId(), comment);
                threadView.addCommentView(commentView);
                displayedComments.put(thread.getId(), comment);
            }
            view.addDiscussionThreadView(threadView);
        }
    }

    private CommentView createCommentView(ThreadId threadId, Comment comment) {
        return commentViewFactory.createAndInitView(
                comment,
                () -> handleReplyToComment(threadId),
                () -> handleEditComment(threadId, comment),
                () -> handleDeleteComment(threadId, comment)
        );
    }

    public void handleCreateThread() {
        entity.ifPresent(targetEntity -> {
            CommentEditorDialog dlg = commentEditorDialogProvider.get();
            dlg.show((body) -> dispatch.execute(
                    createEntityDiscussionThread(projectId, targetEntity, body),
                    result -> displayThreads(result.getThreads())
            ));
        });
    }

    private void handleReplyToComment(ThreadId threadId) {
        CommentEditorDialog dlg = commentEditorDialogProvider.get();
        dlg.show((body) -> dispatch.execute(
                addEntityComment(projectId, threadId, body),
                result -> handleCommentAdded(threadId, result.getComment()))
        );

    }

    private void handleEditComment(ThreadId threadId, Comment comment) {
        CommentEditorDialog dlg = commentEditorDialogProvider.get();
        dlg.setCommentBody(comment.getBody());
        dlg.show((body) -> {

        });
    }

    private void handleDeleteComment(ThreadId threadId, Comment comment) {
        showYesNoConfirmBox("Delete Comment?",
                            "Are you sure that you want to delete this comment?  This cannot be undone.",
                            () -> {
                                // TODO: Delete comment
                            });
    }

    private void handleCommentAdded(ThreadId threadId, Comment comment) {
        if (displayedComments.get(threadId).contains(comment)) {
            return;
        }
        DiscussionThreadView view = discussionThreadViewMap.get(threadId);
        if (view != null) {
            CommentView commentView = createCommentView(threadId, comment);
            view.addCommentView(commentView);
        }
    }

}
