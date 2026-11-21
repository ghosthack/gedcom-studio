package ged.desktop;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.function.IntFunction;

/// The ancestors and descendants pages: a heading with the generations to
/// show, over a scrollable [TreeChart].
final class ChartView {

    static JComponent ancestors(Place.Ancestors place, Model.Pedigree data, Actions actions) {
        var root = TreeChart.ancestors(data, place.depth());
        return page("Ancestors of " + data.person().displayName(),
                "Father above mother, generation by generation. Click someone to centre the chart on them.",
                place.depth(), new int[] {2, 3, 4, 5, 6},
                d -> new Place.Ancestors(place.key(), d), root, data.person(), true, actions);
    }

    static JComponent descendants(Place.Descendants place, Model.Descendants data, Actions actions) {
        var root = TreeChart.descendants(data);
        return page("Descendants of " + data.person().displayName(),
                "Each family with its partner, then its children. Click someone to centre the chart on them.",
                place.depth(), new int[] {1, 2, 3, 4, 5},
                d -> new Place.Descendants(place.key(), d), root, data.person(), false, actions);
    }

    private static JComponent page(String title, String subtitle, int depth, int[] depths, IntFunction<Place> at,
                                   TreeChart.Node root, Model.PersonSummary who, boolean ancestors, Actions actions) {
        var page = new JPanel(new BorderLayout());
        page.setBackground(Ui.pageBackground());

        var head = Ui.transparent(new BorderLayout(16, 0));
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.hairline()),
                BorderFactory.createEmptyBorder(Ui.PAGE_PAD - 6, Ui.PAGE_PAD, 16, Ui.PAGE_PAD)));
        var text = Ui.stack(4);
        var t = Ui.heading(title);
        t.setFont(Ui.font(6, Font.BOLD));
        t.setIcon(ancestors ? Icons.ancestors(22) : Icons.descendants(22));
        t.setIconTextGap(10);
        text.add(t);
        text.add(Ui.mutedLabel(subtitle));
        head.add(text, BorderLayout.CENTER);

        var controls = Ui.row(6);
        controls.add(Ui.mutedLabel("Generations"));
        controls.add(javax.swing.Box.createHorizontalStrut(6));
        var group = new ButtonGroup();
        for (int d : depths) {
            var b = new JToggleButton(String.valueOf(d), d == depth);
            b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            b.putClientProperty(FlatClientProperties.STYLE, "selectedBackground: $Component.accentColor; selectedForeground: #fff; arc: 8");
            b.setFocusable(false);
            b.setToolTipText(d + (d == 1 ? " generation" : " generations"));
            b.setMargin(new java.awt.Insets(3, 9, 3, 9));
            b.addActionListener(e -> actions.go(at.apply(d)));
            group.add(b);
            controls.add(b);
        }
        controls.add(javax.swing.Box.createHorizontalStrut(14));
        controls.add(Ui.button("Open " + who.given().strip().split("\\s+")[0], Icons.user(14), () -> actions.openPerson(who.key())));
        var east = Ui.transparent(new java.awt.GridBagLayout());
        east.add(controls);
        head.add(east, BorderLayout.EAST);
        page.add(head, BorderLayout.NORTH);

        var chart = new TreeChart(root, !ancestors, actions.images(), new TreeChart.Listener() {
            @Override
            public void person(Model.PersonSummary p, boolean isRoot, java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    actions.personMenu(e.getComponent(), e.getX(), e.getY(), p, null, null);
                } else if (isRoot) {
                    actions.openPerson(p.key());
                } else {
                    actions.go(at.apply(depth) instanceof Place.Ancestors a
                            ? new Place.Ancestors(p.key(), a.depth()) : new Place.Descendants(p.key(), depth));
                }
            }

            @Override
            public void empty(String owner, String relation) {
                actions.addRelative(owner, relation);
            }
        });
        var scroll = new JScrollPane(chart);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Ui.pageBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        page.add(scroll, BorderLayout.CENTER);
        chart.revealRoot();
        return page;
    }

    private ChartView() {
    }
}
