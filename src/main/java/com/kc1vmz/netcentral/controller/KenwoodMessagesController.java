package com.kc1vmz.netcentral.controller;
import com.kc1vmz.netcentral.operations.KenwoodMessages;
import org.springframework.web.bind.annotation.*;
@RestController
public class KenwoodMessagesController {
    private final KenwoodMessages messages;
    public KenwoodMessagesController(KenwoodMessages messages){this.messages=messages;}
    @GetMapping("/api/v1/communications/installationMessages")
    public Object messages(){return messages.list();}
}
