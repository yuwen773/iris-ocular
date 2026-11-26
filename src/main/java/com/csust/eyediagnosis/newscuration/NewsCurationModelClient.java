package com.csust.eyediagnosis.newscuration;

/**
 * Single-attempt SPI for invoking a curation model on a frozen {@link NewsCurationModels.EvidenceSnapshot}.
 *
 * <p>Implementations MUST execute at most one network request per {@link #invoke} call without implicit
 * retries; retry budgeting and unknown-outcome recovery belong exclusively to {@link NewsCurationService}.
 */
@FunctionalInterface
public interface NewsCurationModelClient {

    NewsCurationModels.ModelCallOutcome invoke(NewsCurationModels.ModelCallRequest request);
}

/**
 * Default fail-closed model client when no authorized provider adapter is configured in Spring context.
 */
class DisabledNewsCurationModelClient implements NewsCurationModelClient {

    @Override
    public NewsCurationModels.ModelCallOutcome invoke(NewsCurationModels.ModelCallRequest request) {
        return new NewsCurationModels.DefinitiveFailure(
                "MODEL_CLIENT_UNCONFIGURED", null, null, false, 0);
    }
}
