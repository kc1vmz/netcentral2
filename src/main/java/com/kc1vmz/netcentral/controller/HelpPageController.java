package com.kc1vmz.netcentral.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HelpPageController {
    public record Tutorial(String slug, String title) {}
    public record TutorialGroup(String title, List<Tutorial> tutorials) {}

    private static final List<TutorialGroup> GROUPS = List.of(
            new TutorialGroup("Configuration", List.of(
                    new Tutorial("configure-radio", "Configuring communication via radio"),
                    new Tutorial("configure-internet", "Configuring communication via Internet"))),
            new TutorialGroup("Nets", List.of(
                    new Tutorial("create-net", "Create a net"),
                    new Tutorial("schedule-net", "Schedule a net"),
                    new Tutorial("net-messages", "Send messages to a net"),
                    new Tutorial("net-questions-and-answers", "Questions and Answers in a net"),
                    new Tutorial("secure-net", "Secure a net"),
                    new Tutorial("check-in-net", "Checking into a net"),
                    new Tutorial("operate-net", "Operating in a net"))),
            new TutorialGroup("Objects", List.of(
                    new Tutorial("create-priority-object", "Creating a priority object"),
                    new Tutorial("create-general-object", "Creating a general resource object"),
                    new Tutorial("create-generic-object", "Creating a generic APRS object"))),
            new TutorialGroup("Tools", List.of(
                    new Tutorial("winlink-messages", "Sending messages to Winlink"),
                    new Tutorial("nts-radiograms", "Sending NTS Radiograms"))));

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("tutorialGroups", GROUPS);
        return "help";
    }

    @GetMapping("/help/tutorials/{slug}")
    public String tutorial(@PathVariable String slug, Model model) {
        for (TutorialGroup group : GROUPS) {
            for (Tutorial tutorial : group.tutorials()) {
                if (tutorial.slug().equals(slug)) {
                    model.addAttribute("tutorial", tutorial);
                    model.addAttribute("category", group.title());
                    return "tutorial";
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tutorial not found");
    }
}
