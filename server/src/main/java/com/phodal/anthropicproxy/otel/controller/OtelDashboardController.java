package com.phodal.anthropicproxy.otel.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * OTEL Trace UI.
 * Renders a web page that consumes the OTEL JSON APIs from {@link OtelController}.
 */
@Controller
@RequestMapping("/otel/ui")
@RequiredArgsConstructor
public class OtelDashboardController {

    @GetMapping("")
    public String dashboard(
            Model model,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        model.addAttribute("defaultLimit", limit);
        return "otel-dashboard";
    }
}

