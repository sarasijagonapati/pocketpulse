package com.example.pocketpulse;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PocketPulseAccessibilityService extends AccessibilityService {
    private static final String TAG = "PP_TRACKER";
    private PocketPulseRepository repository;
    private final Pattern amountPattern = Pattern.compile("(?:₹|Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    private String lastCapturedAmount = "";
    private long lastTriggeredTime = 0;

    private final Handler settleHandler = new Handler(Looper.getMainLooper());
    private Runnable settleRunnable;

    // FIX 4: Retry mechanism — tries up to 5 times every 500ms until recipient name is found
    private int retryCount = 0;
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_INTERVAL_MS = 500L;
    private static final long INITIAL_DELAY_MS = 1000L; // FIX 1: Increased from 600ms to 1000ms

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new PocketPulseRepository(this);
        Log.d(TAG, "🟢 PocketPulse Accessibility Service successfully started!");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // Cancel any pending scan
            if (settleRunnable != null) {
                settleHandler.removeCallbacks(settleRunnable);
            }

            // Reset retry counter on new screen event
            retryCount = 0;

            // FIX 1: Start with 1000ms initial delay instead of 600ms
            settleRunnable = () -> attemptRead();
            settleHandler.postDelayed(settleRunnable, INITIAL_DELAY_MS);
        }
    }

    // FIX 4: Separated reading into its own method so it can be retried
    private void attemptRead() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        List<String> screenTexts = new ArrayList<>();
        traverseNodeTree(rootNode, screenTexts);
        rootNode.recycle();

        Log.d(TAG, "📄 Screen texts (attempt " + (retryCount + 1) + "): " + screenTexts);

        if (!verifySuccessState(screenTexts)) return;
        if (!isTransactionFromToday(screenTexts)) return;
        if (!isTransactionWithinTimeWindow(screenTexts)) return;

        // FIX 4: Try to parse — if recipient is missing, retry
        boolean parsed = parseAndProcessScreen(screenTexts);

        if (!parsed && retryCount < MAX_RETRIES) {
            retryCount++;
            Log.d(TAG, "🔄 Recipient not found, retrying... attempt " + retryCount);
            settleHandler.postDelayed(this::attemptRead, RETRY_INTERVAL_MS);
        }
    }

    private void traverseNodeTree(AccessibilityNodeInfo node, List<String> texts) {
        if (node == null) return;
        if (node.getText() != null) {
            String val = node.getText().toString().trim();
            if (!val.isEmpty()) {
                texts.add(val);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            traverseNodeTree(node.getChild(i), texts);
        }
    }

    private boolean verifySuccessState(List<String> textNodes) {
        boolean hasSuccessWord = false;
        boolean isIncomingMoney = false;
        boolean isHistoryOrListScreen = false;

        for (String node : textNodes) {
            String text = node.toLowerCase().trim();

            if (text.contains("my statements") || text.contains("transaction history") ||
                    text.contains("all transactions") || text.equals("search") || text.equals("history")) {
                isHistoryOrListScreen = true;
            }

            if (text.contains("successful") || text.contains("paid successfully") ||
                    text.contains("money sent") || text.contains("payment successful") ||
                    text.contains("paid to")) {
                hasSuccessWord = true;
            }

            if (text.contains("received from") || text.contains("credited to")) {
                isIncomingMoney = true;
            }
        }

        return hasSuccessWord && !isIncomingMoney && !isHistoryOrListScreen;
    }

    // FIX 2: Returns boolean — true if amount+recipient found, false if we should retry
    private boolean parseAndProcessScreen(List<String> nodes) {
        String amountStr = "";
        String extractedRecipient = "";

        // FIX 2: First pass — collect ALL valid merchant name candidates from the ENTIRE list
        // PhonePe puts the recipient name early in the tree, not always near the amount
        List<String> recipientCandidates = new ArrayList<>();
        for (String node : nodes) {
            if (isValidMerchantName(node)) {
                recipientCandidates.add(node);
                Log.d(TAG, "👤 Recipient candidate: " + node);
            }
        }

        // Second pass — find the amount
        for (int i = 0; i < nodes.size(); i++) {
            String cleanLine = nodes.get(i);
            String lowerLine = cleanLine.toLowerCase();

            if (lowerLine.contains("balance") || lowerLine.contains("xxxx") || lowerLine.contains("utr")) {
                continue;
            }

            Matcher matcher = amountPattern.matcher(cleanLine);
            if (matcher.find()) {
                amountStr = matcher.group(1).replace(",", "");
                Log.d(TAG, "🎯 Amount found: " + amountStr);

                // FIX 2: Try nearby nodes first (original logic)
                for (int offset = 1; offset <= 3; offset++) {
                    if (i - offset >= 0 && isValidMerchantName(nodes.get(i - offset))) {
                        extractedRecipient = nodes.get(i - offset);
                        break;
                    }
                }
                if (extractedRecipient.isEmpty()) {
                    for (int offset = 1; offset <= 3; offset++) {
                        if (i + offset < nodes.size() && isValidMerchantName(nodes.get(i + offset))) {
                            extractedRecipient = nodes.get(i + offset);
                            break;
                        }
                    }
                }

                // FIX 2: Fallback — use first candidate from full-list scan
                if (extractedRecipient.isEmpty() && !recipientCandidates.isEmpty()) {
                    extractedRecipient = recipientCandidates.get(0);
                    Log.d(TAG, "💡 Used full-scan fallback for recipient: " + extractedRecipient);
                }

                break;
            }
        }

        if (amountStr.isEmpty()) {
            Log.d(TAG, "❌ Amount not found on screen.");
            return false; // retry
        }

        // FIX 4: If recipient is still empty, signal retry (don't show popup with wrong data)
        if (extractedRecipient.isEmpty()) {
            Log.d(TAG, "⚠️ Amount found but recipient still empty — will retry.");
            return false;
        }

        long currentTime = System.currentTimeMillis();
        if (amountStr.equals(lastCapturedAmount) && (currentTime - lastTriggeredTime < 6000)) {
            Log.d(TAG, "🛡️ Duplicate debounced.");
            return true; // don't retry, just skip
        }

        lastCapturedAmount = amountStr;
        lastTriggeredTime = currentTime;

        final double finalAmount = Double.parseDouble(amountStr);
        final String finalRecipient = extractedRecipient;

        Log.d(TAG, "🚀 Launching popup — Amount: ₹" + finalAmount + " | Recipient: " + finalRecipient);

        repository.checkDuplicateTransaction(finalAmount, isDuplicate -> {
            if (!isDuplicate) {
                Intent dialogIntent = new Intent(this, TransactionCategorizerActivity.class);
                dialogIntent.putExtra("EXTRA_AMOUNT", finalAmount);
                dialogIntent.putExtra("EXTRA_RECIPIENT", finalRecipient);
                dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(dialogIntent);
                Log.d(TAG, "🎉 Popup launched successfully!");
            } else {
                Log.d(TAG, "🛑 Duplicate transaction — skipped.");
            }
        });

        return true; // success, no retry needed
    }

    // FIX 3: Improved — no longer blocks emoji names, checks only actual junk patterns
    private boolean isValidMerchantName(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String check = input.toLowerCase().trim();

        // Block known junk keywords
        if (check.contains("successful") || check.contains("paid successfully") ||
                check.contains("debited") || check.contains("sent") || check.contains("received") ||
                check.contains("credited") || check.contains("utr") || check.contains("transaction id") ||
                check.contains("ref") || check.contains("banking name") || check.contains("powered by") ||
                check.contains("history") || check.contains("balance") || check.contains("view") ||
                check.contains("share") || check.contains("money") || check.contains("transfer details") ||
                check.contains("payment") || check.contains("split expense") ||
                check.equals("to") || check.equals("from") || check.equals("done")) {
            return false;
        }

        // Block UPI IDs
        if (check.contains("@")) return false;

        // FIX 3: Block strings that are PURELY numbers (phone numbers, amounts)
        // but ALLOW names that happen to have some digits (e.g. "Shop123")
        // Old regex check.matches(".*\\d{3,}.*") was too aggressive — blocked valid names near phone numbers
        if (check.matches("^[\\d\\s+\\-().]+$")) return false; // pure number string

        // Block masked card numbers
        if (check.contains("xxxx")) return false;

        // Must be at least 2 characters
        return input.trim().length() > 2;
    }

    private boolean isTransactionFromToday(List<String> textNodes) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.text.SimpleDateFormat fullDateFormat = new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH);
        java.text.SimpleDateFormat shortDateFormat = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH);

        String todayFull = fullDateFormat.format(cal.getTime()).toLowerCase();
        String todayShort = shortDateFormat.format(cal.getTime()).toLowerCase();

        for (String node : textNodes) {
            String text = node.toLowerCase();
            if (text.contains(todayFull) || text.contains(todayShort) || text.contains("today")) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransactionWithinTimeWindow(List<String> textNodes) {
        Pattern timePattern = Pattern.compile("\\b([0-9]{1,2}):([0-9]{2})\\s*(am|pm|AM|PM)\\b");

        java.util.Calendar now = java.util.Calendar.getInstance();
        int currentHour = now.get(java.util.Calendar.HOUR);
        int currentMinute = now.get(java.util.Calendar.MINUTE);
        int currentAmPm = now.get(java.util.Calendar.AM_PM);

        for (String node : textNodes) {
            Matcher matcher = timePattern.matcher(node);
            if (matcher.find()) {
                try {
                    int receiptHour = Integer.parseInt(matcher.group(1));
                    int receiptMinute = Integer.parseInt(matcher.group(2));
                    String amPmStr = matcher.group(3).toLowerCase();
                    int receiptAmPm = amPmStr.contains("pm") ? java.util.Calendar.PM : java.util.Calendar.AM;

                    if (currentAmPm != receiptAmPm) continue;

                    int currentTotalMinutes = (currentHour == 12 ? 0 : currentHour) * 60 + currentMinute;
                    int receiptTotalMinutes = (receiptHour == 12 ? 0 : receiptHour) * 60 + receiptMinute;

                    int minuteDifference = Math.abs(currentTotalMinutes - receiptTotalMinutes);

                    if (minuteDifference <= 4) {
                        return true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing time string", e);
                }
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {}
}