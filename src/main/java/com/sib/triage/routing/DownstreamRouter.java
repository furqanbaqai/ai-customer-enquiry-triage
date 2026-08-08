package com.sib.triage.routing;

import com.sib.triage.domain.CustomerEnquiry;
import com.sib.triage.domain.TriageResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface DownstreamRouter {
    CompletionStage<Void> route(CustomerEnquiry enquiry, TriageResult result, String correlationId);
}
