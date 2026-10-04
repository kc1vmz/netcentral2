package com.kc1vmz.netcentral.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.kc1vmz.netcentral.accessor.ApplicationSettingsAccessor;

@Controller
public class HomeController {
    private final ApplicationSettingsAccessor settings;

    public HomeController(ApplicationSettingsAccessor settings) {
        this.settings = settings;
    }

    @GetMapping({ "/", "/home" })
    public String home(Model model) {
        model.addAttribute("applicationName", settings.applicationName());
        return "home";
    }
}
