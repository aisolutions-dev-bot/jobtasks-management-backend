package com.aisolutions.jobtaskmanagement.service.jobtask;

/**
 * HTML email template builder for Job Task assignment/completion notifications.
 * Follows the same violet/slate AI Solutions theme used by vendor-registration-backend's
 * {@code RegistrationEmailTemplate} and vendor-management-backend's {@code InvoiceEmailTemplate}.
 *
 * Design constraints:
 * - Inline styles only (email clients strip &lt;style&gt; tags)
 * - font-family: Arial, sans-serif consistently
 * - Detail rows rendered in a dashed-border violet-accent card, matching the vendor pattern
 */
public class JobTaskEmailTemplate {

    // ========== Color Constants ==========

    private static final String COLOR_PRIMARY = "#7c3aed";
    private static final String COLOR_PRIMARY_SOFT = "#ede9fe";
    private static final String COLOR_BG = "#f8fafc";
    private static final String COLOR_CARD = "#ffffff";
    private static final String COLOR_BORDER = "#e2e8f0";
    private static final String COLOR_TEXT = "#0f172a";
    private static final String COLOR_TEXT_MUTED = "#64748b";
    private static final String COLOR_TEXT_LIGHT = "#94a3b8";

    /** fg/bg/border triples matching the Job Tasks MFE's PRIORITIES palette (constants.ts). */
    private static final java.util.Map<String, String[]> PRIORITY_BADGE_COLORS = java.util.Map.of(
            "Low",    new String[] { "#52525B", "#EFEEEA", "#D8D6D1" },
            "Medium", new String[] { "#1E40AF", "#E2EAF7", "#BCCDEB" },
            "High",   new String[] { "#9A3412", "#FCEBDC", "#F4CBA3" },
            "Urgent", new String[] { "#991B1B", "#FBDDDD", "#F0B5B5" }
    );

    // ========== Common Helpers ==========

    /** Builds the email header with violet background bar and title. */
    private static String buildHeader(String title) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='margin:0;padding:0;background-color:" + COLOR_BG + ";font-family:Arial,sans-serif;'>"
            + "<div style='background-color:" + COLOR_PRIMARY + ";padding:20px;text-align:center;'>"
            + "<h1 style='color:#ffffff;margin:0;font-size:20px;'>" + escHtml(title) + "</h1></div>"
            + "<div style='max-width:600px;margin:0 auto;padding:24px;'>";
    }

    /** Builds the email footer with auto-notice disclaimer. */
    private static String buildFooter() {
        return "</div><div style='max-width:600px;margin:0 auto;padding:16px 24px;text-align:center;color:" + COLOR_TEXT_LIGHT + ";font-size:12px;border-top:1px solid " + COLOR_BORDER + ";'>"
            + "<p style='margin:0;'>This is an automated notification from AI Solutions PL. Please do not reply to this email.</p></div></body></html>";
    }

    /** Builds a greeting paragraph. */
    private static String buildGreeting(String greeting) {
        return "<p style='color:" + COLOR_TEXT + ";font-size:14px;line-height:1.5;'>" + escHtml(greeting) + ",</p>";
    }

    /** Builds a body paragraph with standard text styling. */
    private static String buildParagraph(String text) {
        return "<p style='color:" + COLOR_TEXT + ";font-size:14px;line-height:1.5;'>" + text + "</p>";
    }

    /** Builds a dotted-border detail card with violet accent, matching the vendor template style. */
    private static String buildDetailCard(String content) {
        return "<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;margin:16px 0;'>"
            + "<tr><td style='border:2px dashed " + COLOR_BORDER + ";border-left:4px solid " + COLOR_PRIMARY + ";background-color:" + COLOR_PRIMARY_SOFT + ";padding:16px;border-radius:4px;'>"
            + content
            + "</td></tr></table>";
    }

    /** Builds a labeled detail row for task info display. Skips rendering when the value is blank. */
    private static String buildDetailRow(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<tr><td style='color:" + COLOR_TEXT_MUTED + ";font-size:12px;padding:4px 8px 4px 0;white-space:nowrap;vertical-align:top;width:120px;'>" + escHtml(label) + "</td>"
            + "<td style='color:" + COLOR_TEXT + ";font-size:14px;padding:4px 0;'>" + escHtml(value) + "</td></tr>";
    }

    /** Escapes HTML special characters to prevent injection in email bodies. */
    private static String escHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Falls back to a placeholder dash when a value is absent. */
    private static String safe(String value) {
        return value != null ? value : "-";
    }

    /**
     * Renders the priority value as a color-coded badge — a bordered rounded-rectangle (not a
     * pill), using the fg/bg/border triple from {@link #PRIORITY_BADGE_COLORS}. Falls back to
     * a neutral slate tone when the priority isn't recognized.
     */
    private static String buildPriorityBadge(String priority) {
        if (priority == null || priority.isBlank()) return "-";
        String[] colors = PRIORITY_BADGE_COLORS.getOrDefault(priority, new String[] { "#3F3F46", "#E8E7E4", "#CFCDC8" });
        return "<span style='display:inline-block;padding:3px 10px;border-radius:4px;font-size:12px;font-weight:bold;"
                + "border:1px solid " + colors[2] + ";"
                + "color:" + colors[0] + ";background-color:" + colors[1] + ";'>" + escHtml(priority) + "</span>";
    }

    /**
     * Builds a labeled detail row whose value is pre-rendered HTML (e.g. a pill) rather than
     * plain text — skips escaping since the caller already produced safe markup.
     */
    private static String buildDetailRowHtml(String label, String valueHtml) {
        return "<tr><td style='color:" + COLOR_TEXT_MUTED + ";font-size:12px;padding:4px 8px 4px 0;white-space:nowrap;vertical-align:top;width:120px;'>" + escHtml(label) + "</td>"
            + "<td style='color:" + COLOR_TEXT + ";font-size:14px;padding:4px 0;'>" + valueHtml + "</td></tr>";
    }

    /**
     * Builds the shared task-detail card used by both the assigned and completed emails.
     *
     * Delegates the Priority row to {@link #buildPriorityBadge} via {@link #buildDetailRowHtml},
     * the rest to {@link #buildDetailRow}, then wraps the table in {@link #buildDetailCard}.
     */
    private static String buildTaskDetailsCard(
            String jobTaskId, String taskTitle, String priority, String dueDate,
            String description, String completedDate) {
        StringBuilder rows = new StringBuilder();
        rows.append("<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;'>");
        rows.append(buildDetailRow("Task", jobTaskId + " — " + taskTitle));
        if (priority != null) rows.append(buildDetailRowHtml("Priority", buildPriorityBadge(priority)));
        rows.append(buildDetailRow("Due Date", dueDate));
        rows.append(buildDetailRow("Description", description));
        rows.append(buildDetailRow("Completed On", completedDate));
        rows.append("</table>");
        return buildDetailCard(rows.toString());
    }

    // ========== 1. Task Assigned Notification ==========

    /**
     * Builds the "task assigned" email sent to the assignee.
     *
     * Delegates to {@link #buildTaskDetailsCard} for the task-detail card, then wraps
     * greeting + intro + card in the shared {@link #buildHeader}/{@link #buildFooter}.
     */
    public static String buildAssignedEmail(
            String assigneeName, String assignorName, String jobTaskId, String taskTitle,
            String priority, String dueDate, String description) {

        String detailsCard = buildTaskDetailsCard(
                jobTaskId, taskTitle, safe(priority), dueDate, description, null);

        return buildHeader("New Task Assigned")
            + buildGreeting("Hi " + assigneeName)
            + buildParagraph("A new task has been assigned to you by <strong>" + escHtml(assignorName) + "</strong>:")
            + detailsCard
            + buildParagraph("Please log in to the Job Tasks portal to view the full details and get started.")
            + buildFooter();
    }

    // ========== 2. Task Completed Notification ==========

    /**
     * Builds the "task completed" email sent to the assignor.
     *
     * Delegates to {@link #buildTaskDetailsCard} for the task-detail card, then wraps
     * greeting + intro + card in the shared {@link #buildHeader}/{@link #buildFooter}.
     */
    public static String buildCompletedEmail(
            String assignorName, String assigneeName, String jobTaskId, String taskTitle,
            String completedDate) {

        String detailsCard = buildTaskDetailsCard(
                jobTaskId, taskTitle, null, null, null, completedDate);

        return buildHeader("Task Completed")
            + buildGreeting("Hi " + assignorName)
            + buildParagraph("<strong>" + escHtml(assigneeName) + "</strong> has completed the task you assigned:")
            + detailsCard
            + buildParagraph("No further action is required unless you'd like to review the completed work.")
            + buildFooter();
    }
}
