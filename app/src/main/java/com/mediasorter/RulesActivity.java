package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.mediasorter.organizer.AutoOrganizer;
import com.mediasorter.organizer.Rule;
import java.util.List;

public class RulesActivity extends Activity {
    private AutoOrganizer organizer;
    private List<Rule> rules;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        organizer = new AutoOrganizer(this,
                ((App)getApplication()).getTagManager(),
                ((App)getApplication()).getBatchRenameManager(),
                ((App)getApplication()).getFileStatus());

        rules = organizer.getRules();
        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                getRuleNames());
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> editRule(pos));
        setContentView(lv);

        findViewById(android.R.id.content).setOnLongClickListener(v -> {
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
        r.action = null; // will set in editor
        rules.add(r);
        organizer.setRules(rules);
        adapter.clear();
        adapter.addAll(getRuleNames());
    }

    private void editRule(int pos) {
        // For now just toggle enabled, full editor later
        Rule r = rules.get(pos);
        r.enabled = !r.enabled;
        organizer.setRules(rules);
        adapter.clear();
        adapter.addAll(getRuleNames());
    }
}
