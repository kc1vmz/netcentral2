package com.kc1vmz.netcentral.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.accessor.AccountAccessor;
import com.kc1vmz.netcentral.account.Account;
import com.kc1vmz.netcentral.account.AccountEdit;
import com.kc1vmz.netcentral.account.Registration;

@Controller
public class AccountPageController {
    private final AccountAccessor accounts;

    public AccountPageController(AccountAccessor accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String registration() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@ModelAttribute Registration registration) {
        accounts.register(registration);
        return "redirect:/login?registered";
    }

    @GetMapping("/profile")
    public String profile(Model model) {
        model.addAttribute("account", accounts.current());
        model.addAttribute("administrator", accounts.administrator());
        model.addAttribute("editingOther", false);
        model.addAttribute("accountAction", "/profile");
        return "profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@RequestParam String emailAddress, @RequestParam String firstName,
            @RequestParam String lastName, @RequestParam String callsign,
            @RequestParam(defaultValue = "") String password,
            @RequestParam(defaultValue = "") String currentPassword) {
        Account actor = accounts.current();
        accounts.update(actor.id(), new AccountEdit(emailAddress, firstName, lastName,
                new Account.Callsign(callsign), null, password, currentPassword));
        return "redirect:/login?updated";
    }

    @PostMapping("/profile/delete")
    public String deleteProfile() {
        accounts.delete(accounts.current().id());
        return "redirect:/login?deleted";
    }

    @GetMapping("/users")
    public String users(Model model) {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator access required");
        model.addAttribute("accounts", accounts.list());
        model.addAttribute("actor", accounts.current());
        return "users";
    }

    @PostMapping("/users")
    public String create(@ModelAttribute Registration registration, @RequestParam(defaultValue = "USER") String role) {
        accounts.create(registration, role);
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/role")
    public String role(@PathVariable String id, @RequestParam String role) {
        Account target = accounts.get(id);
        accounts.update(id, new AccountEdit(target.emailAddress(), target.firstName(), target.lastName(),
                target.callsign(), role, null, null));
        return "redirect:/users";
    }

    @GetMapping("/users/{id}")
    public String editUser(@PathVariable String id, Model model) {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator access required");
        model.addAttribute("account", accounts.get(id));
        model.addAttribute("administrator", true);
        model.addAttribute("editingOther", !accounts.current().id().equals(id));
        model.addAttribute("accountAction", "/users/" + id);
        return "profile";
    }

    @PostMapping("/users/{id}")
    public String updateUser(@PathVariable String id, @RequestParam String emailAddress,
            @RequestParam String firstName, @RequestParam String lastName,
            @RequestParam String callsign, @RequestParam(defaultValue = "") String password,
            @RequestParam(defaultValue = "") String currentPassword) {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator access required");
        boolean self = accounts.current().id().equals(id);
        accounts.update(id, new AccountEdit(emailAddress, firstName, lastName,
                new Account.Callsign(callsign), null, password, currentPassword));
        return self ? "redirect:/login?updated" : "redirect:/users";
    }

    @PostMapping("/users/{id}/delete")
    public String delete(@PathVariable String id) {
        accounts.delete(id);
        return "redirect:/users";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String invalid(ResponseStatusException exception, HttpServletResponse response, Model model) {
        response.setStatus(exception.getStatusCode().value());
        model.addAttribute("message", exception.getReason());
        return "account-error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String denied(HttpServletResponse response, Model model) {
        response.setStatus(403);
        model.addAttribute("message", "You do not have permission to make this change.");
        return "account-error";
    }
}
