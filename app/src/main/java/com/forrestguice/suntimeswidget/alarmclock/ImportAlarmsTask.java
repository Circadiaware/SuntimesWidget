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

package com.forrestguice.suntimeswidget.alarmclock;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;

/**
 * AsyncTask that reads AlarmClockItem objects from text file (json array).
 * @see AlarmClockItem
 */
public class ImportAlarmsTask extends AsyncTask<Uri, AlarmClockItem, ImportAlarmsTask.TaskResult>
{
    public static final long MIN_WAIT_TIME = 2000;

    private WeakReference<Context> contextRef;

    protected boolean isPaused = false;
    public void pauseTask() {
        isPaused = true;
    }
    public void resumeTask() {
        isPaused = false;
    }
    public boolean isPaused() {
        return isPaused;
    }

    public ImportAlarmsTask(Context context)
    {
        contextRef = new WeakReference<>(context);
    }

    @Override
    protected void onPreExecute()
    {
        Log.d(getClass().getSimpleName(), "onPreExecute");
        if (taskListener != null) {
            taskListener.onStarted();
        }
    }

    @Override
    protected TaskResult doInBackground(Uri... params)
    {
        Log.d(getClass().getSimpleName(), "doInBackground: starting");
        Uri uri = null;
        if (params.length > 0) {
            uri = params[0];
        }

        long startTime = System.currentTimeMillis();
        boolean result = false;
        ArrayList<AlarmClockItem> items = new ArrayList<>();
        Exception error = null;

        Context context = contextRef.get();
        if (context != null && uri != null)
        {
            try {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in != null)
                {
                    Log.d(getClass().getSimpleName(), "doInBackground: reading");

                    String jsonString = inputStreamToString(in);
                    JSONArray jsonArray = new JSONArray(jsonString);
                    in.close();

                    for (int i=0; i<jsonArray.length(); i++)
                    {
                        JSONObject jsonObj = jsonArray.getJSONObject(i);
                        Map<String, Object> map = new HashMap<>();
                        for (Iterator<String> iterator = jsonObj.keys(); iterator.hasNext(); )
                        {
                            String key = iterator.next();
                            Object obj = jsonObj.get(key);
                            if (obj != null) {
                                map.put(key, obj);
                            }
                        }

                        ContentValues values = toContentValues(map);
                        AlarmClockItem item = new AlarmClockItem();
                        item.fromContentValues(context, values);
                        items.add(item);
                    }

                    result = true;
                    error = null;

                } else {
                    Log.e(getClass().getSimpleName(), "Failed to import from " + uri + ": null input stream!");
                    result = false;
                    error = null;
                }
            } catch (IOException | JSONException e) {
                Log.e(getClass().getSimpleName(), "Failed to import from " + uri + ": " + e);
                result = false;
                items = null;
                error = e;
            }
        }

        Log.d(getClass().getSimpleName(), "doInBackground: waiting");
        long endTime = System.currentTimeMillis();
        while ((endTime - startTime) < MIN_WAIT_TIME || isPaused) {
            endTime = System.currentTimeMillis();
        }

        Log.d(getClass().getSimpleName(), "doInBackground: finishing");
        return new TaskResult(result, uri, (items != null ? items.toArray(new AlarmClockItem[0]) : null), error);
    }

    @Override
    protected void onProgressUpdate(AlarmClockItem... progressItems) {
        super.onProgressUpdate(progressItems);
    }

    @Override
    protected void onPostExecute( TaskResult result )
    {
        Log.d(getClass().getSimpleName(), "onPostExecute: " + result.getResult());
        if (taskListener != null) {
            taskListener.onFinished(result);
        }
    }

    public static String inputStreamToString(InputStream in)
    {
        Scanner s = new Scanner(in).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    /* https://stackoverflow.com/a/59211956 */
    public static ContentValues toContentValues(Map<String, Object> map)
    {
        ContentValues values = new ContentValues();
        for (Map.Entry<String, Object> entry : map.entrySet())
        {
            String key = entry.getKey();
            Object obj = entry.getValue();

            if (obj instanceof Integer) {
                values.put(key, (Integer) obj);

            } else if (obj instanceof Long) {
                values.put(key, (Long) obj);

            } else if (obj instanceof Short) {
                values.put(key, (Short) obj);

            } else if (obj instanceof Float) {
                values.put(key, (Float) obj);

            } else if (obj instanceof Double) {
                values.put(key, (Double) obj);

            } else if (obj instanceof Byte) {
                values.put(key, (Byte) obj);

            } else if (obj instanceof Boolean) {
                values.put(key, (Boolean) obj);

            } else if (obj instanceof String) {
                values.put(key, (String) obj);
            }
        }
        return values;
    }

    /**
     * TaskResult
     */
    public static class TaskResult
    {
        public TaskResult(boolean result, Uri uri, @Nullable AlarmClockItem[] items, Exception e)
        {
            this.result = result;
            this.items = items;
            this.uri = uri;
            this.e = e;
        }

        private boolean result;
        public boolean getResult()
        {
            return result;
        }

        private AlarmClockItem[] items;
        public AlarmClockItem[] getItems()
        {
            return items;
        }

        private Uri uri;
        public Uri getUri()
        {
            return uri;
        }

        public int numResults() {
            return (items != null ? items.length : 0);
        }

        private Exception e;
        public Exception getException()
        {
            return e;
        }
    }

    /**
     * TaskListener
     */
    public static abstract class TaskListener
    {
        public void onStarted() {}
        public void onFinished( TaskResult result ) {}
    }
    protected TaskListener taskListener = null;
    public void setTaskListener( TaskListener listener ) {
        taskListener = listener;
    }
    public void clearTaskListener() {
        taskListener = null;
    }

}