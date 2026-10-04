package com.kc1vmz.netcentral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kc1vmz.netcentral.accessor.LiveViewAccessor;
import com.kc1vmz.netcentral.account.LocalPrincipal;
import com.kc1vmz.netcentral.operations.LiveEventStream;

@RestController
@RequestMapping("/api/v1/live")
public class LiveViewController {
    private final LiveViewAccessor views;
    private final LiveEventStream events;

    public LiveViewController(LiveViewAccessor views, LiveEventStream events) {
        this.views = views;
        this.events = events;
    }

    @GetMapping("/snapshot")
    public LiveViewAccessor.Snapshot snapshot(@AuthenticationPrincipal LocalPrincipal principal) {
        return views.snapshot(principal);
    }

    @GetMapping(value = "/events", produces = "text/event-stream")
    public SseEmitter events(@AuthenticationPrincipal LocalPrincipal principal, HttpServletRequest request) {
        return events.open(principal, request.getSession(false));
    }
}
