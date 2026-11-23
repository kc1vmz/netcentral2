package com.kc1vmz.netcentral.controller;

import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.accessor.NetSchedulerAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.ui.Model;

final class NetMonitorNavigation {
    record Entry(String url, String name, String callsign, String kind) {}
    static List<Entry> entries(NetAccessor nets, NetSchedulerAccessor schedules) {
        var entries = new ArrayList<Entry>();
        for (var n : nets.list(false)) entries.add(new Entry("/nets/" + n.id(), n.name(), n.callsign(), "Running net"));
        for (var s : schedules.list()) entries.add(new Entry("/schedules/" + s.id(), s.name(), s.callsign(), "Scheduled"));
        entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::callsign, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::url));
        return entries;
    }
    static void populate(Model model, NetAccessor nets, NetSchedulerAccessor schedules, String url) {
        var entries = entries(nets, schedules);
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).url().equals(url)) {
            model.addAttribute("monitorEntry", entries.get(i));
            model.addAttribute("monitorPrevious", entries.size() > 1 ? entries.get((i + entries.size() - 1) % entries.size()).url() : null);
            model.addAttribute("monitorNext", entries.size() > 1 ? entries.get((i + 1) % entries.size()).url() : null);
            return;
        }
    }
    static String afterDeletion(NetAccessor nets, NetSchedulerAccessor schedules, String url) {
        var entries = entries(nets, schedules);
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).url().equals(url))
            return entries.size() > 1 ? entries.get((i + 1) % entries.size()).url() : "/nets";
        return "/nets";
    }
}
