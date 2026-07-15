package com.mediasorter;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.mediasorter.organizer.AutoOrganizer;
import com.mediasorter.organizer.Rule;
import java.util.ArrayList;
import java.util.List;

public class RulesActivity extends Activity {
    private AutoOrganizer organizer;
    private List<Rule> rules;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Instantiate managers directly (they read from SharedPreferences, so it's fine)
        TagManager tagManager = new TagManager(this);
        BatchRenameManager renamer = new BatchRenameManager();
        FileStatus fileStatus = new FileStatus(this);

        organizer = new AutoOrganizer(this, tagManager, renamer, fileStatus);
        rules = organizer.getRules();

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                getRuleNames());
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> editRule(pos));
        setContentView(lv);

        lv.setOnLongClickListener(v -> {
            addNewRule();
            return true;
        });
    }

    private List<String> getRuleNames() {
        List<String> names = new ArrayList<>();
        for (Rule r : rules) names.add((r.enabled ? "✓ " : "✗ ") + r.name);
        return names;
    }

    private void addNewRule() {
        Rule r = new Rule();
        r.name = "New Rule";
        rules.add(r);
        organizer.setRules(rules);
        adapter.clear();
        adapter.addAll(getRuleNames());
    }

    private void editRule(int pos) {
        Rule r = rules.get(pos);
        r.enabled = !r.enabled;
        organizer.setRules(rules);
        adapter.clear();
        adapter.addAll(getRuleNames());
    }
}
