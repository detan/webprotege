package edu.stanford.bmir.protege.web.server.reasoning;

import com.beust.jcommander.internal.Lists;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import edu.stanford.bmir.protege.web.server.dispatch.AbstractHasProjectActionHandler;
import edu.stanford.bmir.protege.web.server.dispatch.ExecutionContext;
import edu.stanford.bmir.protege.web.server.dispatch.RequestContext;
import edu.stanford.bmir.protege.web.server.dispatch.RequestValidator;
import edu.stanford.bmir.protege.web.server.dispatch.validators.UserHasProjectReadPermissionValidator;
import edu.stanford.bmir.protege.web.server.logging.WebProtegeLogger;
import edu.stanford.bmir.protege.web.server.logging.WebProtegeLoggerManager;
import edu.stanford.bmir.protege.web.server.mansyntax.WebProtegeOWLEntityChecker;
import edu.stanford.bmir.protege.web.server.owlapi.OWLAPIProject;
import edu.stanford.bmir.protege.web.shared.entity.OWLClassData;
import edu.stanford.bmir.protege.web.shared.entity.OWLEntityData;
import edu.stanford.bmir.protege.web.shared.frame.HasFreshEntities;
import edu.stanford.bmir.protege.web.shared.project.ProjectId;
import edu.stanford.bmir.protege.web.shared.reasoning.*;
import edu.stanford.bmir.protege.web.shared.revision.RevisionNumber;
import edu.stanford.protege.reasoning.*;
import edu.stanford.protege.reasoning.action.*;
import edu.stanford.protege.reasoning.action.Consistency;
import org.apache.http.concurrent.FutureCallback;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxClassExpressionParser;
import org.semanticweb.owlapi.expression.OWLExpressionParser;
import org.semanticweb.owlapi.expression.ParserException;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.TimeOutException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.net.ConnectException;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * @author Matthew Horridge, Stanford University, Bio-Medical Informatics Research Group, Date: 04/09/2014
 */
public class ExecuteDLQueryActionHandler extends AbstractHasProjectActionHandler<ExecuteDLQueryAction, ExecuteDLQueryResult> {

    private ReasoningService reasoningService;

    private final WebProtegeLogger logger;

    @Inject
    public ExecuteDLQueryActionHandler(ReasoningService reasoningService) {
        this.reasoningService = reasoningService;
        logger = WebProtegeLoggerManager.get(ExecuteDLQueryActionHandler.class);
    }

    @Override
    protected RequestValidator<ExecuteDLQueryAction> getAdditionalRequestValidator(ExecuteDLQueryAction
                                                                                                      action,
                                                                                          RequestContext
                                                                                                  requestContext) {
        return UserHasProjectReadPermissionValidator.get();
    }

    @Override
    protected ExecuteDLQueryResult execute(ExecuteDLQueryAction action,
                                                  OWLAPIProject project,
                                                  ExecutionContext executionContext) {
        final ProjectId projectId = project.getProjectId();
        try {
            final KbId kbId = new KbId(projectId.getId());

            ListenableFuture<GetKbDigestResponse> digestFuture = reasoningService.execute(new GetKbDigestAction(kbId));
            KbDigest kbDigest = digestFuture.get().getKbDigest();
            logger.info(projectId, "I've been asked to execute a DL query (%s).  " +
                    "I'm checking to see if the reasoner is empty.", action.getEnteredClassExpression());
            if(kbDigest.equals(KbDigest.emptyDigest())) {
                logger.info(projectId, "The reasoner is empty and needs synchronizing. Going to do this.  Returning an empty result.");
                project.synchronizeReasoner();
                return new ExecuteDLQueryResult(projectId, new ReasonerBusy<DLQueryResult>());
            }
            else {
                logger.info(projectId, "The reasoner is not empty.");
            }

            ListenableFuture<IsConsistentResponse> consFuture = reasoningService.execute(new IsConsistentAction(kbId));
            Optional<edu.stanford.protege.reasoning.action.Consistency> consistency = consFuture.get()
                                                                                                 .getConsistency();
            if(!consistency.isPresent()) {
                return new ExecuteDLQueryResult(projectId, new ReasonerBusy<DLQueryResult>());
            }
            Consistency cons = consistency.get();
            if(cons == Consistency.INCONSISTENT) {
                return new ExecuteDLQueryResult(projectId,
                        new ProjectInconsistent<DLQueryResult>()
                );
            }


            OWLExpressionParser<OWLClassExpression> classExpressionParser = new ManchesterOWLSyntaxClassExpressionParser(
                    project.getDataFactory(),
                    new WebProtegeOWLEntityChecker(project.getRenderingManager().getShortFormProvider(), new HasFreshEntities() {

                        @Override
                        public Set<OWLEntityData> getFreshEntities() {
                            return Collections.emptySet();
                        }
                    })
            );

            OWLClassExpression ce = null;
            try {
                ce = classExpressionParser.parse(action.getEnteredClassExpression());
            } catch (ParserException e) {
                return new ExecuteDLQueryResult(projectId, new MalformedQuery<DLQueryResult>(e.getMessage()));
            }

            List<DLQueryResultsSectionHandler<?,?,?,?>> handlers = Lists.newArrayList();
            handlers.add(new DirectSuperClassesSectionHandler());
            handlers.add(new EquivalentClassesSectionHandler());
            handlers.add(new DirectSubClassesSectionHandler());
            handlers.add(new DirectInstancesSectionHandler());

            List<ListenableFuture<DLQueryEntitySetResult>> resultFutures = Lists.newArrayList();
            for(DLQueryResultsSectionHandler<?,?,?,?> handler : handlers) {
                ListenableFuture<DLQueryEntitySetResult> future = handler.executeQuery(kbId, ce, reasoningService, project);
                resultFutures.add(future);
            }
            ListenableFuture<List<DLQueryEntitySetResult>> futures = Futures.allAsList(resultFutures);
            List<DLQueryEntitySetResult> results = futures.get();

            ImmutableList.Builder<DLQueryEntitySetResult> resultList = ImmutableList.builder();
            for(DLQueryEntitySetResult result : results) {
                   resultList.add(result);
            }
            Optional<RevisionNumber> revisionNumber = results.get(0).getRevisionNumber();

            return new ExecuteDLQueryResult(projectId,
                                            new ReasonerQueryResult<DLQueryResult>(
                                                    revisionNumber.get(),
                                                    new DLQueryResult(resultList.build())));

        } catch (ReasonerInternalErrorException e) {
            return new ExecuteDLQueryResult(projectId, new ReasonerError<DLQueryResult>(e.getMessage()));
        } catch (TimeOutException e) {
            return new ExecuteDLQueryResult(projectId, new ReasonerTimeOut<DLQueryResult>());
        } catch (InterruptedException e) {
            return new ExecuteDLQueryResult(projectId, new ReasonerError<DLQueryResult>("Reasoning was interrupted."));
        } catch (ExecutionException e) {
            return new ExecuteDLQueryResult(projectId, new ReasonerError<DLQueryResult>());
        } catch (ConcurrentModificationException e) {
            return new ExecuteDLQueryResult(projectId, new ReasonerError<DLQueryResult>());
        } catch (RuntimeException e) {
            return new ExecuteDLQueryResult(projectId, new ReasonerError<DLQueryResult>());
        }
    }



    @Override
    public Class<ExecuteDLQueryAction> getActionClass() {
        return ExecuteDLQueryAction.class;
    }
}
