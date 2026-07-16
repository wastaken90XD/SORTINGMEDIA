package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import com.mediasorter.organizer.AutoOrganizer;
import com.mediasorter.organizer.Rule;
import com.mediasorter.organizer.Action;
import com.mediasorter.organizer.Condition;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class RulesActivity extends Activity {
    private AutoOrganizer organizer;
    private List<Rule> rules;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TagManager tagManager = new TagManager(this);
        BatchRenameManager renamer = new BatchRenameManager();
        FileStatus fileStatus = new FileStatus(this);
        organizer = new AutoOrganizer(this, tagManager, renamer, fileStatus);

        rules = organizer.getRules();
        if (rules == null) rules = new ArrayList<>();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);

        ListView listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                getRuleDescriptions()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFCCCCCC);
                return tv;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> toggleRule(pos));
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        Button addBtn = new Button(this);
        addBtn.setText("+ Test Rule");
        addBtn.setOnClickListener(v -> addTestRule());
        buttonRow.addView(addBtn);

        Button runBtn = new Button(this);
        runBtn.setText("Run Now");
        runBtn.setOnClickListener(v -> runOrganizer());
        buttonRow.addView(runBtn);

        Button logBtn = new Button(this);
        logBtn.setText("Log");
        logBtn.setOnClickListener(v -> showLog());
        buttonRow.addView(logBtn);

        root.addView(buttonRow);
        setContentView(root);
    }

    private List<String> getRuleDescriptions() {
        List<String> descs = new ArrayList<>();
        for (Rule r : rules) {
            String status = r.enabled ? "✓ " : "✗ ";
            descs.add(status + r.name + " — " + (r.action != null ? r.action.describe() : "no action"));
        }
        return descs;
    }

    private void toggleRule(int pos) {
        Rule r = rules.get(pos);
        r.enabled = !r.enabled;
        organizer.setRules(rules);
        adapter.clear();
        adapter.addAll(getRuleDescriptions());
    }

    private void addTestRule() {
        Rule r = new Rule();
        r.name = "Test: tag 'test' → add 'organized'";
        List<String> tags = new ArrayList<>();
        tags.add("test");
        r.conditions.add(Condition.tagCondition(tags, true, false));
        List<String> addTags = new ArrayList<>();
        addTags.add("organized");
        r.action = Action.tagAction(addTags, null);
        r.enabled = true;

        rules.add(r);
        organizer.setRules(rules);
        adapter.clear();
        adapter.addAll(getRuleDescriptions());
        Toast.makeText(this, "Test rule added", Toast.LENGTH_SHORT).show();
    }

    private void runOrganizer() {
        List<MediaFile> files = MainActivity.getLatestFullList();
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "No files to organize", Toast.LENGTH_SHORT).show();
            return;
        }
        int affected = organizer.applyTo(files);
        Toast.makeText(this, "Rules applied. Files affected: " + affected, Toast.LENGTH_SHORT).show();
    }

    private void showLog() {
        List<String> log = organizer.getLog();
        if (log.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : log) sb.append(line).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("Organizer Log")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }
}
