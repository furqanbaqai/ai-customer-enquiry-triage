package com.sib.triage.support;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Prints a concise, human-readable scenario and result for every unit test.
 * This deliberately uses stdout so descriptions remain visible in local Maven
 * builds and CI logs without requiring a particular logging configuration.
 */
public final class ConsoleTestDescription implements BeforeTestExecutionCallback, AfterTestExecutionCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ConsoleTestDescription.class);

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        context.getStore(NAMESPACE).put(context.getUniqueId(), System.nanoTime());
        System.out.printf("%n[TEST START] %s%n  Scenario: %s%n",
                context.getRequiredTestClass().getSimpleName(), humanize(context.getRequiredTestMethod().getName()));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        var startedAt = context.getStore(NAMESPACE).remove(context.getUniqueId(), Long.class);
        var elapsedMillis = startedAt == null ? 0 : (System.nanoTime() - startedAt) / 1_000_000;
        var failure = context.getExecutionException();
        if (failure.isEmpty()) {
            System.out.printf("[TEST PASS ] %s (%d ms)%n", context.getDisplayName(), elapsedMillis);
        } else {
            System.out.printf("[TEST FAIL ] %s (%d ms)%n  Cause: %s: %s%n",
                    context.getDisplayName(), elapsedMillis, failure.get().getClass().getSimpleName(),
                    failure.get().getMessage());
        }
    }

    private static String humanize(String methodName) {
        var words = methodName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1) + ".";
    }
}
