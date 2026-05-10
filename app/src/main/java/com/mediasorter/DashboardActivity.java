package com.mediasorter;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DashboardActivity extends Activity {

    private MediaIndexer indexer;
    private TagManager   tagManager;
    private CacheManager cacheManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        indexer      = new MediaIndexer();
        tagManager   = new TagManager(this);
        cacheManager = new CacheManager(this);
        buildDashboard();
    }

    private void buildDashboard() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(24, 24, 24, 24);

        List<MediaFile> files = indexer.getIndex();
        List<Tag>       tags  = tagManager.getAllTags();

        root.addView(makeTitle("Dashboard"));

        root.addView(makeLabel(
            "Cache: " + cacheManager.getFormattedCacheSize()
            + " / " + cacheManager.getLimitMB() + " MB"
            + (cacheManager.isAboveWarning() ? "  ⚠ Near limit" : "")
        ));

        root.addView(makeTitle("File Composition"));
        root.addView(buildCompositionPie(files));

        root.addView(makeTitle("Tag Distribution"));
        root.addView(buildTagBar(tags));

        root.addView(makeTitle("Tagging Progress"));
        root.addView(buildProgressLine(files));

        root.addView(makeTitle("Tag Co-occurrence"));
        root.addView(buildCoOccurrenceTable(tags, files));

        root.addView(makeTitle("File Size Ranges"));
        root.addView(buildSizeRangeBar(files));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private PieChart buildCompositionPie(List<MediaFile> files) {
        int images = 0, videos = 0, other = 0;
        for (MediaFile f : files) {
            switch (f.getType()) {
                case IMAGE: images++; break;
                case VIDEO: videos++; break;
                default:    other++;  break;
            }
        }

        List<PieEntry> entries = new ArrayList<>();
        if (images > 0) entries.add(new PieEntry(images, "Images"));
        if (videos > 0) entries.add(new PieEntry(videos, "Videos"));
        if (other  > 0) entries.add(new PieEntry(other,  "Other"));

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
        dataSet.setValueTextColor(0xFFFFFFFF);
        dataSet.setValueTextSize(12f);

        PieChart chart = new PieChart(this);
        chart.setData(new PieData(dataSet));
        chart.setBackgroundColor(0xFF1A1A2E);
        chart.setHoleColor(0xFF121212);
        chart.getLegend().setTextColor(0xFFFFFFFF);
        chart.getDescription().setEnabled(false);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400));
        chart.invalidate();
        return chart;
    }

    private BarChart buildTagBar(List<Tag> tags) {
        List<BarEntry> entries = new ArrayList<>();
        List<String>   labels  = new ArrayList<>();

        List<Tag> top = tags.size() > 10 ? tags.subList(0, 10) : tags;
        for (int i = 0; i < top.size(); i++) {
            entries.add(new BarEntry(i, top.get(i).getUsageCount()));
            labels.add(top.get(i).getName());
        }

        BarDataSet dataSet = new BarDataSet(entries, "Tag Usage");
        dataSet.setColor(0xFFE94560);
        dataSet.setValueTextColor(0xFFFFFFFF);

        BarChart chart = new BarChart(this);
        chart.setData(new BarData(dataSet));
        chart.setBackgroundColor(0xFF1A1A2E);
        chart.getXAxis().setTextColor(0xFFFFFFFF);
        chart.getAxisLeft().setTextColor(0xFFFFFFFF);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setTextColor(0xFFFFFFFF);
        chart.getDescription().setEnabled(false);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400));
        chart.invalidate();
        return chart;
    }

    private LineChart buildProgressLine(List<MediaFile> files) {
        int total   = files.size();
        int tagged  = 0;
        for (MediaFile f : files) {
            if (!f.getTags().isEmpty()) tagged++;
        }
        int untagged = total - tagged;

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(0, 0));
        entries.add(new Entry(1, tagged));
        entries.add(new Entry(2, total));

        LineDataSet dataSet = new LineDataSet(entries, "Tagged vs Total");
        dataSet.setColor(0xFFE94560);
        dataSet.setCircleColor(0xFFE94560);
        dataSet.setValueTextColor(0xFFFFFFFF);
        dataSet.setLineWidth(2f);

        LineChart chart = new LineChart(this);
        chart.setData(new LineData(dataSet));
        chart.setBackgroundColor(0xFF1A1A2E);
        chart.getXAxis().setTextColor(0xFFFFFFFF);
        chart.getAxisLeft().setTextColor(0xFFFFFFFF);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setTextColor(0xFFFFFFFF);
        chart.getDescription().setEnabled(false);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 300));
        chart.setNoDataText("Tagged: " + tagged + "  Untagged: " + untagged);
        chart.invalidate();
        return chart;
    }

    private View buildCoOccurrenceTable(List<Tag> tags, List<MediaFile> files) {
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackgroundColor(0xFF1A1A2E);
        table.setPadding(16, 16, 16, 16);

        List<Tag> top = tags.size() > 5 ? tags.subList(0, 5) : tags;

        for (Tag tag : top) {
            Map<String, Integer> coMap =
                tagManager.computeCoOccurrences(tag.getName(), files);

            if (coMap.isEmpty()) continue;

            TextView tagLabel = makeLabel("▸ " + tag.getName());
            tagLabel.setTextColor(0xFFE94560);
            table.addView(tagLabel);

            for (Map.Entry<String, Integer> e : coMap.entrySet()) {
                table.addView(makeLabel(
                    "    " + e.getKey() + "  ×" + e.getValue()));
            }
        }

        if (top.isEmpty()) {
            table.addView(makeLabel("No tag data yet"));
        }

        return table;
    }

    private BarChart buildSizeRangeBar(List<MediaFile> files) {
        int tiny   = 0;
        int small  = 0;
        int medium = 0;
        int large  = 0;

        for (MediaFile f : files) {
            long mb = f.getSize() / (1024 * 1024);
            if      (mb < 1)   tiny++;
            else if (mb < 10)  small++;
            else if (mb < 100) medium++;
            else               large++;
        }

        List<BarEntry> entries = new ArrayList<>();
        entries.add(new BarEntry(0, tiny));
        entries.add(new BarEntry(1, small));
        entries.add(new BarEntry(2, medium));
        entries.add(new BarEntry(3, large));

        BarDataSet dataSet = new BarDataSet(entries, "File Sizes");
        dataSet.setColors(ColorTemplate.COLORFUL_COLORS);
        dataSet.setValueTextColor(0xFFFFFFFF);

        BarChart chart = new BarChart(this);
        chart.setData(new BarData(dataSet));
        chart.setBackgroundColor(0xFF1A1A2E);
        chart.getXAxis().setTextColor(0xFFFFFFFF);
        chart.getAxisLeft().setTextColor(0xFFFFFFFF);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setTextColor(0xFFFFFFFF);
        chart.getDescription().setEnabled(false);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400));
        chart.invalidate();
        return chart;
    }

    private TextView makeTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE94560);
        tv.setTextSize(16f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin    = 24;
        lp.bottomMargin = 8;
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 4;
        tv.setLayoutParams(lp);
        return tv;
    }
}
