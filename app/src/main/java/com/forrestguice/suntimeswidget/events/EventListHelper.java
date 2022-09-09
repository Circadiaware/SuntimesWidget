/**
    Copyright (C) 2022 Forrest Guice
    This file is part of SuntimesWidget.

    SuntimesWidget is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    SuntimesWidget is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with SuntimesWidget.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.forrestguice.suntimeswidget.events;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.PopupMenu;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.forrestguice.suntimeswidget.ExportTask;
import com.forrestguice.suntimeswidget.HelpDialog;
import com.forrestguice.suntimeswidget.R;
import com.forrestguice.suntimeswidget.SuntimesUtils;
import com.forrestguice.suntimeswidget.alarmclock.AlarmEventProvider;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class EventListHelper
{
    public static final String DIALOGTAG_ADD = "add";
    public static final String DIALOGTAG_EDIT = "edit";
    public static final String DIALOGTAG_HELP = "help";

    private WeakReference<Context> contextRef;
    private android.support.v4.app.FragmentManager fragmentManager;

    private int selectedChild = -1;
    private EventSettings.EventAlias selectedItem;
    private ListView list;
    private EventDisplayAdapterInterface adapter;
    protected ActionMode actionMode = null;
    protected EventAliasActionMode1 actionModeCallback = new EventAliasActionMode1();

    protected ProgressBar progress;
    protected View progressLayout;

    protected boolean adapterModified = false;
    public boolean isAdapterModified() {
        return adapterModified;
    }

    public EventListHelper(@NonNull Context context, @NonNull android.support.v4.app.FragmentManager fragments)
    {
        contextRef = new WeakReference<>(context);
        setFragmentManager(fragments);
    }

    private View.OnClickListener onItemSelected = null;
    public void setOnItemAcceptedListener(View.OnClickListener listener) {
        onItemSelected = listener;
    }

    private View.OnClickListener onUpdateViews = null;
    public void setOnUpdateViews(View.OnClickListener listener) {
        onUpdateViews = listener;
    }

    public void setFragmentManager(android.support.v4.app.FragmentManager fragments) {
        fragmentManager = fragments;
    }

    private boolean disallowSelect = false;
    public void setDisallowSelect( boolean value ) {
        disallowSelect = value;
    }

    private boolean expanded = false;
    public void setExpanded( boolean value ) {
        expanded = value;
    }

    public void setSelected( String eventID ) {
        Log.d("DEBUG", "setSelected: " + eventID);
        selectedItem = adapter.findItemByID(eventID);
        adapter.setSelected(selectedItem);
    }

    public void onRestoreInstanceState(Bundle savedState)
    {
        expanded = savedState.getBoolean("expanded", expanded);
        disallowSelect = savedState.getBoolean("disallowSelect", disallowSelect);
        adapterModified = savedState.getBoolean("adapterModified", adapterModified);

        String eventID = savedState.getString("selectedItem");
        if (eventID != null && !eventID.trim().isEmpty()) {
            setSelected(eventID);
            triggerActionMode(list, adapter.getSelected(), adapter.getSelectedChild());
        }
    }

    public void onSaveInstanceState( Bundle outState )
    {
        outState.putBoolean("expanded", expanded);
        outState.putBoolean("disallowSelect", disallowSelect);
        outState.putBoolean("adapterModified", adapterModified);
        outState.putString("selectedItem", selectedItem != null ? selectedItem.getID() : "");
    }

    public void onResume()
    {
        EditEventDialog addDialog = (EditEventDialog) fragmentManager.findFragmentByTag(DIALOGTAG_ADD);
        if (addDialog != null) {
            addDialog.setOnAcceptedListener(onEventSaved(contextRef.get(), addDialog));
        }

        EditEventDialog editDialog = (EditEventDialog) fragmentManager.findFragmentByTag(DIALOGTAG_EDIT);
        if (editDialog != null) {
            editDialog.setOnAcceptedListener(onEventSaved(contextRef.get(), editDialog));
        }
    }

    public String getEventID()
    {
        if (list != null) {
            EventSettings.EventAlias selected = adapter.getSelected();
            return selected != null ? selected.getID() : null;
        } else return null;
    }

    public String getAliasUri()
    {
        if (list != null)
        {
            String suffix = "";
            if (selectedChild >= 0) {
                suffix = ((selectedChild == 0) ? AlarmEventProvider.ElevationEvent.SUFFIX_RISING : AlarmEventProvider.ElevationEvent.SUFFIX_SETTING);
            }

            EventSettings.EventAlias selected = adapter.getSelected();
            return selected != null ? selected.getAliasUri() + suffix : null;
        } else return null;
    }

    public String getLabel()
    {
        if (list != null) {
            EventSettings.EventAlias selected = (EventSettings.EventAlias) list.getSelectedItem();
            return selected != null ? selected.getLabel() : null;
        } else return null;
    }

    public ListView getListView() {
        return list;
    }

    protected void updateViews(Context context)
    {
        if (onUpdateViews != null) {
            onUpdateViews.onClick(list);
        }
    }

    public void initViews(Context context, View content, @Nullable Bundle savedState)
    {
        if (content == null) {
            return;
        }

        list = expanded ? (ListView) content.findViewById(R.id.explist_events) : (ListView) content.findViewById(R.id.list_events);
        list.setVisibility(View.VISIBLE);

        progress = (ProgressBar) content.findViewById(R.id.progress);
        progressLayout = content.findViewById(R.id.progressLayout);
        showProgress(false);

        if (expanded)
        {
            final ExpandableListView expandedList = (ExpandableListView) list;

            expandedList.setOnGroupExpandListener(new ExpandableListView.OnGroupExpandListener() {
                @Override
                public void onGroupExpand(int groupPosition)
                {
                    for (int i=0; i<expandedList.getCount(); i++) {
                        if (i != groupPosition) {
                            expandedList.collapseGroup(i);
                        }
                    }
                    adapter.setSelected(selectedItem = (EventSettings.EventAlias) expandedList.getAdapter().getItem(groupPosition));
                    adapter.setSelected(selectedChild = 0);
                    updateViews(contextRef.get());
                    triggerActionMode(expandedList.getSelectedView(), selectedItem, selectedChild);
                }
            });

            expandedList.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View view, int groupPosition, int childPosition, long id)
                {
                    adapter.setSelected(selectedItem = (EventSettings.EventAlias) expandedList.getAdapter().getItem(groupPosition));
                    adapter.setSelected(selectedChild = childPosition);
                    updateViews(contextRef.get());
                    triggerActionMode(view, selectedItem, childPosition);
                    return true;
                }
            });

        } else {
            list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id)
                {
                    list.setSelection(position);
                    adapter.setSelected(selectedItem = (EventSettings.EventAlias) list.getItemAtPosition(position));
                    updateViews(contextRef.get());
                    triggerActionMode(view, selectedItem);
                }
            });
        }
        initAdapter(context);

        //ImageButton button_menu = (ImageButton) content.findViewById(R.id.edit_event_menu);
        //if (button_menu != null) {
        //    button_menu.setOnClickListener(onMenuButtonClicked);
        //}

        if (savedState != null) {
            onRestoreInstanceState(savedState);
        }
    }

    protected void initAdapter(Context context)
    {
        List<EventSettings.EventAlias> events = EventSettings.loadEvents(context, AlarmEventProvider.EventType.SUN_ELEVATION);
        Collections.sort(events, new Comparator<EventSettings.EventAlias>() {
            @Override
            public int compare(EventSettings.EventAlias o1, EventSettings.EventAlias o2) {
                return o1.getID().compareTo(o2.getID());
            }
        });

        if (expanded)
        {
            ExpandableEventDisplayAdapter adapter0 = new ExpandableEventDisplayAdapter(context, R.layout.layout_listitem_events, R.layout.layout_listitem_events1, events);
            ExpandableListView expandedList = (ExpandableListView) list;
            expandedList.setAdapter(adapter0);
            adapter = adapter0;

        } else {
            EventDisplayAdapter adapter0 = new EventDisplayAdapter(context, R.layout.layout_listitem_events, events.toArray(new EventSettings.EventAlias[0]));
            list.setAdapter(adapter0);
            adapter = adapter0;
        }
    }

    protected View.OnClickListener onMenuButtonClicked = new View.OnClickListener() {

        @Override
        public void onClick(View v) {
            showOverflowMenu(contextRef.get(), v);
        }
    };

    protected void showOverflowMenu(Context context, View parent)
    {
        PopupMenu menu = new PopupMenu(context, parent);
        MenuInflater inflater = menu.getMenuInflater();
        inflater.inflate(R.menu.eventlist, menu.getMenu());
        menu.setOnMenuItemClickListener(onMenuItemClicked);
        SuntimesUtils.forceActionBarIcons(menu.getMenu());
        prepareOverflowMenu(context, menu.getMenu());
        menu.show();
    }

    protected void prepareOverflowMenu(Context context, Menu menu)
    {
        String eventID = getEventID();
        boolean isModifiable = eventID != null && !eventID.trim().isEmpty();

        MenuItem editItem = menu.findItem(R.id.editEvent);
        if (editItem != null) {
            editItem.setEnabled(isModifiable);
            editItem.setVisible(isModifiable);
        }

        MenuItem deleteItem = menu.findItem(R.id.deleteEvent);
        if (deleteItem != null) {
            deleteItem.setEnabled(isModifiable);
            deleteItem.setVisible(isModifiable);
        }
    }

    protected PopupMenu.OnMenuItemClickListener onMenuItemClicked = new PopupMenu.OnMenuItemClickListener()
    {
        @Override
        public boolean onMenuItemClick(MenuItem menuItem)
        {
            switch (menuItem.getItemId())
            {
                case R.id.addEvent:
                    addEvent();
                    return true;

                case R.id.editEvent:
                    editEvent(getEventID());
                    return true;

                case R.id.clearEvents:
                    clearEvents();
                    return true;

                case R.id.deleteEvent:
                    deleteEvent(getEventID());
                    return true;

                case R.id.helpEvents:
                    showHelp();
                    return true;

                default:
                    return false;
            }
        }
    };

    public void addEvent()
    {
        final Context context = contextRef.get();
        final EditEventDialog saveDialog = new EditEventDialog();
        saveDialog.setDialogMode(EditEventDialog.DIALOG_MODE_ADD);
        saveDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                saveDialog.setIsModified(false);
            }
        });
        saveDialog.setOnAcceptedListener(onEventSaved(context, saveDialog));
        saveDialog.show(fragmentManager, DIALOGTAG_ADD);
    }

    protected void editEvent(final String eventID)
    {
        final Context context = contextRef.get();
        if (eventID != null && !eventID.trim().isEmpty() && context != null)
        {
            final EditEventDialog saveDialog = new EditEventDialog();
            saveDialog.setDialogMode(EditEventDialog.DIALOG_MODE_EDIT);
            saveDialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface dialog) {
                    saveDialog.setEvent(EventSettings.loadEvent(context, eventID));
                    saveDialog.setIsModified(false);
                }
            });

            saveDialog.setOnAcceptedListener(onEventSaved(context, saveDialog));
            saveDialog.show(fragmentManager, DIALOGTAG_EDIT);
        }
    }

    private DialogInterface.OnClickListener onEventSaved(final Context context, final EditEventDialog saveDialog)
    {
        return new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String eventID = saveDialog.getEventID();
                EventSettings.saveEvent(context, saveDialog.getEvent());
                //Toast.makeText(context, context.getString(R.string.saveevent_toast, saveDialog.getEventLabel(), eventID), Toast.LENGTH_SHORT).show();  // TODO
                initAdapter(context);
                updateViews(context);
                adapterModified = true;

                setSelected(eventID);
                triggerActionMode(list, selectedItem, selectedChild);
            }
        };
    }

    /**
     * ExportTask
     */
    protected EventExportTask exportTask = null;

    public boolean exportEvents(Activity activity)
    {
        if (exportTask != null) { // && importTask != null) {
            Log.e("ExportAlarms", "Already busy importing/exporting! ignoring request");
            return false;
        }

        String exportTarget = "SuntimesEvents";
        Context context = contextRef.get();
        if (context != null && adapter != null)
        {
            EventSettings.EventAlias[] items = getItemsForExport();
            if (items.length > 0)
            {
                if (Build.VERSION.SDK_INT >= 19)
                {
                    String filename = exportTarget + EventExportTask.FILEEXT;
                    Intent intent = ExportTask.getCreateFileIntent(filename, EventExportTask.MIMETYPE);
                    try {
                        activity.startActivityForResult(intent, EventListActivity.REQUEST_EXPORT_URI);
                        return true;

                    } catch (ActivityNotFoundException e) {
                        Log.e("ExportEvents", "SAF is unavailable? (" + e + ").. falling back to legacy export method.");
                    }
                }
                exportTask = new EventExportTask(context, exportTarget, true, true);    // export to external cache
                exportTask.setItems(items);
                exportTask.setTaskListener(exportListener);
                exportTask.execute();
                return true;
            } else return false;
        }
        return false;
    }

    protected void exportEvents(Context context, @NonNull Uri uri)
    {
        if (exportTask != null) { // && importTask != null) {
            Log.e("ExportEvents", "Already busy importing/exporting! ignoring request");

        } else {
            EventSettings.EventAlias[] items = getItemsForExport();
            if (items.length > 0)
            {
                exportTask = new EventExportTask(context, uri);    // export directly to uri
                exportTask.setItems(items);
                exportTask.setTaskListener(exportListener);
                exportTask.execute();
            }
        }
    }

    protected EventSettings.EventAlias[] getItemsForExport()
    {
        List<EventSettings.EventAlias> itemList = adapter.getItems();
        Collections.reverse(itemList);                                                // should be reversed for export (so import encounters/adds older items first)
        return itemList.toArray(new EventSettings.EventAlias[0]);
    }

    private ExportTask.TaskListener exportListener = new ExportTask.TaskListener()
    {
        @Override
        public void onStarted() {
            showProgress(true);
        }

        @Override
        public void onFinished(ExportTask.ExportResult results)
        {
            exportTask = null;
            showProgress(false);

            Context context = contextRef.get();
            if (context != null)
            {
                File file = results.getExportFile();
                String path = ((file != null) ? file.getAbsolutePath() : ExportTask.getFileName(context.getContentResolver(), results.getExportUri()));

                if (results.getResult())
                {
                    //if (isAdded()) {
                        String successMessage = context.getString(R.string.msg_export_success, path);
                        Toast.makeText(context, successMessage, Toast.LENGTH_LONG).show();
                        // TODO: use a snackbar instead; offer 'copy path' action
                    //}

                    if (Build.VERSION.SDK_INT >= 19) {
                        if (results.getExportUri() == null) {
                            ExportTask.shareResult(context, results.getExportFile(), results.getMimeType());
                        }
                    } else {
                        ExportTask.shareResult(context, results.getExportFile(), results.getMimeType());
                    }
                    return;
                }

                //if (isAdded()) {
                    String failureMessage = context.getString(R.string.msg_export_failure, path);
                    Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show();
                //}
            }
        }
    };

    protected void showProgress(boolean visible)
    {
        if (progress != null) {
            progress.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (progressLayout != null) {
            progressLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * ImportTask
     */

    public void importEvents() {
        // TODO
        adapterModified = true;
    }

    protected void importEvents(Context context, @NonNull Uri uri)
    {
        // TODO
    }

    public void clearEvents()
    {
        final Context context = contextRef.get();
        if (context != null)
        {
            AlertDialog.Builder dialog = new AlertDialog.Builder(context);
            dialog.setMessage(context.getString(R.string.clearevents_dialog_msg))
                    .setNegativeButton(context.getString(R.string.clearevents_dialog_cancel), null)
                    .setPositiveButton(context.getString(R.string.clearevents_dialog_ok),
                            new DialogInterface.OnClickListener()
                            {
                                @Override
                                public void onClick(DialogInterface dialog, int which)
                                {
                                    EventSettings.deletePrefs(context);
                                    EventSettings.initDefaults(context);
                                    Toast.makeText(context, context.getString(R.string.clearevents_toast), Toast.LENGTH_SHORT).show();
                                    initAdapter(context);
                                    updateViews(context);
                                    adapterModified = true;
                                }
                            });
            dialog.show();
        }
    }

    protected void deleteEvent(final String eventID)
    {
        final Context context = contextRef.get();
        if (eventID != null && !eventID.trim().isEmpty() && context != null)
        {
            AlertDialog.Builder dialog = new AlertDialog.Builder(context);
            String label = EventSettings.loadEventValue(context, eventID, EventSettings.PREF_KEY_EVENT_LABEL);

            dialog.setMessage(context.getString(R.string.delevent_dialog_msg, label, eventID))
                    .setNegativeButton(context.getString(R.string.delevent_dialog_cancel), null)
                    .setPositiveButton(context.getString(R.string.delevent_dialog_ok),
                            new DialogInterface.OnClickListener()
                            {
                                @Override
                                public void onClick(DialogInterface dialog, int which)
                                {
                                    EventSettings.deleteEvent(context, eventID);
                                    adapterModified = true;

                                    list.post(new Runnable()
                                    {
                                        @Override
                                        public void run() {
                                            Context context = contextRef.get();
                                            if (context != null) {
                                                initAdapter(context);
                                                updateViews(context);
                                            }
                                        }
                                    });
                                }
                            });
            dialog.show();
        }
    }

    public void showHelp()
    {
        HelpDialog helpDialog = new HelpDialog();
        helpDialog.setContent(contextRef.get().getString(R.string.help_eventlist));
        helpDialog.show(fragmentManager, DIALOGTAG_HELP);
    }

    /**
     * AdapterInterface
     */
    public interface EventDisplayAdapterInterface
    {
        EventSettings.EventAlias getSelected();
        int getSelectedChild();
        void setSelected( EventSettings.EventAlias item );
        void setSelected(int i);
        EventSettings.EventAlias findItemByID(String eventID);
        List<EventSettings.EventAlias> getItems();
    }

    /**
     * ExpandableEventDisplayAdapter
     */
    public static class ExpandableEventDisplayAdapter extends BaseExpandableListAdapter implements EventDisplayAdapterInterface
    {
        private WeakReference<Context> contextRef;
        private int groupResourceID, childResourceID;
        private List<EventSettings.EventAlias> objects;
        private EventSettings.EventAlias selectedItem;
        private int selectedChild = -1;

        public ExpandableEventDisplayAdapter(Context context, int groupResourceID, int childResourceID, @NonNull List<EventSettings.EventAlias> objects)
        {
            this.contextRef = new WeakReference<>(context);
            this.groupResourceID = groupResourceID;
            this.childResourceID = childResourceID;
            this.objects = objects;
        }

        @Override
        public int getGroupCount() {
            return objects.size();
        }

        @Override
        public long getGroupId(int groupPosition) {
            return groupPosition;
        }

        @Override
        public Object getGroup(int groupPosition) {
            return objects.get(groupPosition);
        }

        @Override
        public int getChildrenCount(int groupPosition) {
            return 2;
        }

        @Override
        public long getChildId(int groupPosition, int childPosition) {
            return childPosition;
        }

        @Override
        public Object getChild(int groupPosition, int childPosition) {
            return childPosition;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded, View view, ViewGroup parent)
        {
            Context context = contextRef.get();
            if (context == null) {
                return view;
            }
            if (view == null) {
                LayoutInflater layoutInflater = LayoutInflater.from(context);
                view = layoutInflater.inflate(groupResourceID, parent, false);
            }

            view.setPadding((int)context.getResources().getDimension(R.dimen.eventIcon_width), 0, 0, 0);

            EventSettings.EventAlias item = (EventSettings.EventAlias) getGroup(groupPosition);
            if (item == null) {
                Log.w("getGroupView", "group at position " + groupPosition + " is null.");
                return view;
            }

            if (selectedItem != null && item.getID().equals(selectedItem.getID())) {
                view.setBackgroundColor(ContextCompat.getColor(context, R.color.text_accent_dark));
            } else view.setBackgroundColor(Color.TRANSPARENT);


            TextView primaryText = (TextView)view.findViewById(android.R.id.text1);
            if (primaryText != null) {
                primaryText.setText(item.toString());
            }

            TextView secondaryText = (TextView)view.findViewById(android.R.id.text2);
            if (secondaryText != null) {
                secondaryText.setText(item.getSummary(context));
            }

            ImageView icon = (ImageView) view.findViewById(android.R.id.icon1);
            if (icon != null) {
                icon.setBackgroundColor(item.getColor());
            }
            return view;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View view, ViewGroup parent)
        {
            Context context = contextRef.get();
            if (context == null) {
                return view;
            }
            if (view == null) {
                LayoutInflater layoutInflater = LayoutInflater.from(context);
                view = layoutInflater.inflate(childResourceID, parent, false);
            }

            view.setPadding((int)context.getResources().getDimension(R.dimen.eventIcon_width), 0, 0, 0);

            boolean rising = (childPosition == 0);
            EventSettings.EventAlias item = (EventSettings.EventAlias) getGroup(groupPosition);
            String displayString = item.toString() + " " + context.getString(rising ? R.string.eventalias_title_tag_rising : R.string.eventalias_title_tag_setting);  // TODO

            if (selectedItem != null && item.getID().equals(selectedItem.getID()) && (selectedChild == childPosition)) {
                view.setBackgroundColor(ContextCompat.getColor(context, R.color.text_accent_dark));
            } else view.setBackgroundColor(Color.TRANSPARENT);

            TextView primaryText = (TextView)view.findViewById(android.R.id.text1);
            if (primaryText != null) {
                primaryText.setText(displayString);
            }

            ImageView icon = (ImageView) view.findViewById(android.R.id.icon1);
            if (icon != null)
            {
                Drawable drawable = ContextCompat.getDrawable(context, (rising ? R.drawable.svg_sunrise : R.drawable.svg_sunset));
                drawable.setTint(item.getColor());
                icon.setImageDrawable( drawable );
            }

            return view;
        }

        public void setSelected( EventSettings.EventAlias item ) {
            selectedItem = item;
            notifyDataSetChanged();
        }

        @Override
        public void setSelected(int i) {
            selectedChild = i;
        }

        public EventSettings.EventAlias getSelected() {
            return selectedItem;
        }

        public int getSelectedChild() {
            return selectedChild;
        }

        public EventSettings.EventAlias findItemByID(String eventID)
        {
            for (int i=0; i<objects.size(); i++) {
                EventSettings.EventAlias item = objects.get(i);
                if (item != null && item.getID().equals(eventID)) {
                    Log.d("DEBUG", "findItemByID: " + eventID + " .. " + item.toString());
                    return item;
                }
            }
            Log.d("DEBUG", "findItemByID: " + eventID + " .. null");
            return null;
        }

        @Override
        public List<EventSettings.EventAlias> getItems() {
            return objects;
        }
    }

    /**
     * EventDisplayAdapter
     */
    public static class EventDisplayAdapter extends ArrayAdapter<EventSettings.EventAlias> implements EventDisplayAdapterInterface
    {
        private int resourceID, dropDownResourceID;
        private EventSettings.EventAlias selectedItem;

        public EventDisplayAdapter(@NonNull Context context, int resource) {
            super(context, resource);
            init(context, resource);
        }

        public EventDisplayAdapter(@NonNull Context context, int resource, @NonNull EventSettings.EventAlias[] objects) {
            super(context, resource, objects);
            init(context, resource);
        }

        public EventDisplayAdapter(@NonNull Context context, int resource, @NonNull List<EventSettings.EventAlias> objects) {
            super(context, resource, objects);
            init(context, resource);
        }

        private void init(@NonNull Context context, int resource) {
            resourceID = dropDownResourceID = resource;
        }

        public void setSelected( EventSettings.EventAlias item ) {
            selectedItem = item;
            notifyDataSetChanged();
        }

        @Override
        public void setSelected(int i) {
            /* EMPTY */
        }

        public EventSettings.EventAlias getSelected() {
            return selectedItem;
        }

        public int getSelectedChild() {
            return -1;
        }

        public EventSettings.EventAlias findItemByID(String eventID)
        {
            for (int i=0; i<getCount(); i++) {
                EventSettings.EventAlias item = getItem(i);
                if (item != null && item.getID().equals(eventID)) {
                    return item;
                }
            }
            return null;
        }

        @Override
        public List<EventSettings.EventAlias> getItems()
        {
            ArrayList<EventSettings.EventAlias> items = new ArrayList<>();
            for (int i=0; i<getCount(); i++)
            {
                EventSettings.EventAlias item = getItem(i);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        }

        @Override
        public void setDropDownViewResource(int resID) {
            super.setDropDownViewResource(resID);
            dropDownResourceID = resID;
        }

        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            return getItemView(position, convertView, parent, dropDownResourceID);
        }

        @Override
        @NonNull
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            return getItemView(position, convertView, parent, resourceID);
        }

        private View getItemView(int position, View convertView, @NonNull ViewGroup parent, int resID)
        {
            LayoutInflater layoutInflater = LayoutInflater.from(getContext());
            View view = layoutInflater.inflate(resID, parent, false);

            EventSettings.EventAlias item = getItem(position);
            if (item == null) {
                Log.w("getItemView", "item at position " + position + " is null.");
                return view;
            }

            if (selectedItem != null && item.getID().equals(selectedItem.getID())) {
                Log.d("DEBUG", "getItemView: " + selectedItem.getID());
                view.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.text_accent_dark));
            } else view.setBackgroundColor(Color.TRANSPARENT);

            TextView primaryText = (TextView)view.findViewById(android.R.id.text1);
            if (primaryText != null) {
                primaryText.setText(item.toString());
            }

            TextView secondaryText = (TextView)view.findViewById(android.R.id.text2);
            if (secondaryText != null) {
                secondaryText.setText(item.getSummary(getContext()));
            }

            ImageView icon = (ImageView) view.findViewById(android.R.id.icon1);
            if (icon != null) {
                icon.setBackgroundColor(item.getColor());
            }
            return view;
        }
    }

    /**
     * triggerActionMode
     */
    public boolean triggerActionMode() {
        return triggerActionMode(list, selectedItem, selectedChild);
    }
    protected boolean triggerActionMode(View view, EventSettings.EventAlias item) {
        return triggerActionMode(view, item, -1);
    }
    protected boolean triggerActionMode(View view, EventSettings.EventAlias item, int i)
    {
        Context context = contextRef.get();
        if (context == null) {
             return false;
        }

        if (Build.VERSION.SDK_INT >= 11)
        {
            if (actionMode == null)
            {
                if (item != null)
                {
                    setSelected(item.getID());
                    actionModeCallback.setItem(item);
                    actionModeCallback.setItemChild(i);
                    actionMode = list.startActionModeForChild(view, actionModeCallback);
                    if (actionMode != null)
                    {
                        if (i >= 0) {
                            boolean rising = (i == 0);
                            actionMode.setTitle(context.getString(R.string.eventalias_title_format, item.getLabel(), context.getString(rising ? R.string.eventalias_title_tag_rising : R.string.eventalias_title_tag_setting)));
                        } else actionMode.setTitle(item.getLabel());
                    }
                }
                return true;

            } else {
                actionMode.finish();
                triggerActionMode(view, item, i);
                return false;
            }

        } else {
            Toast.makeText(contextRef.get(), "TODO", Toast.LENGTH_SHORT).show();  // TODO: legacy support
            return false;
        }
    }

    /**
     * EventAliasActionMode
     */
    private class EventAliasActionModeBase
    {
        public EventAliasActionModeBase() {
        }

        protected EventSettings.EventAlias event = null;
        public void setItem(EventSettings.EventAlias item) {
            event = item;
        }

        protected int itemChild = -1;
        public void setItemChild(int i) {
            itemChild = i;
        }

        protected boolean onCreateActionMode(MenuInflater inflater, Menu menu) {
            inflater.inflate(R.menu.eventlist1, menu);
            return true;
        }

        protected void onDestroyActionMode() {
            actionMode = null;
            setSelected(null);
        }

        protected boolean onPrepareActionMode(Menu menu)
        {
            SuntimesUtils.forceActionBarIcons(menu);
            MenuItem selectItem = menu.findItem(R.id.selectEvent);
            selectItem.setVisible( !disallowSelect );

            String eventID = event.getID();
            boolean isModifiable = (eventID != null && !eventID.trim().isEmpty());

            MenuItem deleteItem = menu.findItem(R.id.deleteEvent);
            MenuItem editItem = menu.findItem(R.id.editEvent);
            deleteItem.setVisible( isModifiable );
            editItem.setVisible( isModifiable );
            return false;
        }

        protected boolean onActionItemClicked(MenuItem item)
        {
            if (event != null)
            {
                switch (item.getItemId())
                {
                    case R.id.selectEvent:
                        if (onItemSelected != null) {
                            onItemSelected.onClick(list);
                        }
                        return true;

                    case R.id.deleteEvent:
                        deleteEvent(event.getID());
                        return true;

                    case R.id.editEvent:
                        editEvent(event.getID());
                        return true;
                }
            }
            return false;
        }
    }

    private class EventAliasActionMode extends EventAliasActionModeBase implements android.support.v7.view.ActionMode.Callback
    {
        public EventAliasActionMode() {
            super();
        }
        @Override
        public boolean onCreateActionMode(android.support.v7.view.ActionMode mode, Menu menu) {
            return onCreateActionMode(mode.getMenuInflater(), menu);
        }
        @Override
        public void onDestroyActionMode(android.support.v7.view.ActionMode mode) {
            onDestroyActionMode();
        }
        @Override
        public boolean onPrepareActionMode(android.support.v7.view.ActionMode mode, Menu menu) {
            return onPrepareActionMode(menu);
        }
        @Override
        public boolean onActionItemClicked(android.support.v7.view.ActionMode mode, MenuItem item)
        {
            boolean result = onActionItemClicked(item);
            mode.finish();
            return result;
        }
    }

    @TargetApi(11)
    private class EventAliasActionMode1 extends EventAliasActionModeBase implements ActionMode.Callback
    {
        public EventAliasActionMode1() {
            super();
        }
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return onCreateActionMode(mode.getMenuInflater(), menu);
        }
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            onDestroyActionMode();
        }
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return onPrepareActionMode(menu);
        }
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item)
        {
            boolean result = onActionItemClicked(item);
            mode.finish();
            return result;
        }
    }

}
