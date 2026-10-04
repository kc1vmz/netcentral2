package com.kc1vmz.netcentral.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.kc1vmz.netcentral.account.LocalPrincipal;

@ControllerAdvice(assignableTypes = { AccountPageController.class, HomeController.class,
        CommunicationPageController.class, NetPageController.class, SchedulePageController.class,
        OperationsPageController.class, DataController.class })
public class AccountPageAdvice {
    @ModelAttribute("systemAdministrator")
    public boolean systemAdministrator(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof LocalPrincipal
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSADMIN"));
    }

    @ModelAttribute("signedIn")
    public boolean signedIn(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof LocalPrincipal;
    }
}
