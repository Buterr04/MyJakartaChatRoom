package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.util.JsonUtil;

/** Super-admin message statistics; system messages are excluded in the repository query. */
public class StatisticsServlet extends HttpServlet {

    private final ChatRepository repository;

    public StatisticsServlet(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        String granularity = req.getParameter("granularity");
        granularity = granularity == null || granularity.isBlank() ? "day" : granularity.toLowerCase();
        if (!List.of("day", "week", "month").contains(granularity)) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "粒度只能是 day、week 或 month"));
            return;
        }
        try {
            LocalDate to = parseDate(req.getParameter("to"), today);
            LocalDate from = parseDate(req.getParameter("from"), to.minusDays(29));
            if (from.isAfter(to)) {
                JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                        Map.of("error", "开始日期不能晚于结束日期"));
                return;
            }
            ChatRepository.MessageStats stats = repository.loadMessageStats(from, to, zone);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", from.toString());
            body.put("to", to.toString());
            body.put("granularity", granularity);
            body.put("today", Map.of("date", today.toString(), "count", stats.todayCount()));
            body.put("total", stats.totalCount());
            body.put("login", Map.of("today", stats.todayLoginCount(), "total", stats.totalUsers()));
            body.put("activeUsers", stats.activeUsers());
            body.put("series", buildSeries(stats.daily(), from, to, granularity));
            JsonUtil.write(resp, HttpServletResponse.SC_OK, body);
        } catch (DateTimeParseException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "日期格式应为 YYYY-MM-DD"));
        }
    }

    private List<SeriesPoint> buildSeries(List<ChatRepository.DailyCount> daily,
            LocalDate from, LocalDate to, String granularity) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ChatRepository.DailyCount item : daily) {
            LocalDate bucket = bucketStart(LocalDate.parse(item.date()), granularity);
            counts.merge(bucket.toString(), item.count(), Long::sum);
        }
        LocalDate cursor = switch (granularity) {
            case "week" -> from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> from.withDayOfMonth(1);
            default -> from;
        };
        List<SeriesPoint> series = new ArrayList<>();
        while (!cursor.isAfter(to)) {
            series.add(new SeriesPoint(cursor.toString(), label(cursor, granularity),
                    counts.getOrDefault(cursor.toString(), 0L)));
            cursor = switch (granularity) {
                case "week" -> cursor.plusWeeks(1);
                case "month" -> cursor.plusMonths(1);
                default -> cursor.plusDays(1);
            };
        }
        return series;
    }

    private LocalDate bucketStart(LocalDate date, String granularity) {
        return switch (granularity) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private String label(LocalDate date, String granularity) {
        return "month".equals(granularity)
                ? String.format("%04d-%02d", date.getYear(), date.getMonthValue())
                : String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        return value == null || value.isBlank() ? fallback : LocalDate.parse(value);
    }

    public record SeriesPoint(String period, String label, long count) {
    }
}
