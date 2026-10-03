package com.corebanking.integration.core.provider;

import java.util.ArrayList;
import java.util.List;

/** Records requests and answers from a queue: adapter contract tests without a network. */
final class FakeTransport implements HttpTransport {

    final List<Request> requests = new ArrayList<>();
    private final List<Response> answers = new ArrayList<>();
    private ProviderException failure;

    FakeTransport answer(int status, String body) {
        answers.add(new Response(status, body, null));
        return this;
    }

    FakeTransport answer(int status, String body, String location) {
        answers.add(new Response(status, body, location));
        return this;
    }

    FakeTransport fail(ProviderException e) {
        failure = e;
        return this;
    }

    @Override
    public Response send(Request request) {
        requests.add(request);
        if (failure != null) throw failure;
        if (answers.isEmpty()) throw new IllegalStateException("no answer queued for " + request.url());
        return answers.remove(0);
    }
}
