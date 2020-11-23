/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calendar.month;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Message;
import android.text.format.Time;
import android.util.Log;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView.LayoutParams;

import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.Event;
import com.android.calendar.R;
import com.android.calendar.Utils;

import java.util.ArrayList;
import java.util.HashMap;

public class MonthByWeekAdapter extends SimpleWeeksAdapter {
    //private  final String TAG = "MonthByWeekAdapter";

    public static final String WEEK_PARAMS_IS_MINI = "mini_month";
    protected static int DEFAULT_QUERY_DAYS = 7 * 8; // 8 weeks
    private static final long ANIMATE_TODAY_TIMEOUT = 1000;

    protected CalendarController mController;
    protected String mHomeTimeZone;//当前时区
    protected Time mTempTime;
    protected Time mToday;//当前日期
    protected int mFirstJulianDay;
    protected int mQueryDays;
    protected boolean mIsMiniMonth = true;
    protected int mOrientation = Configuration.ORIENTATION_LANDSCAPE;
    private final boolean mShowAgendaWithMonth;

    protected ArrayList<ArrayList<Event>> mEventDayList = new ArrayList<ArrayList<Event>>();
    protected ArrayList<Event> mEvents = null;

    private boolean mAnimateToday = false;
    private long mAnimateTime = 0;

    private Handler mEventDialogHandler;

    MonthWeekEventsView mClickedView;
    MonthWeekEventsView mSingleTapUpView;
    MonthWeekEventsView mLongClickedView;

    float mClickedXLocation;                // Used to find which day was clicked
    long mClickTime;                        // Used to calculate minimum click animation time
    // Used to insure minimal time for seeing the click animation before switching views
    private static final int mOnTapDelay = 100;
    // Minimal time for a down touch action before stating the click animation, this insures that
    // there is no click animation on flings
    private static int mOnDownDelay;
    private static int mTotalClickDelay;
    // Minimal distance to move the finger in order to cancel the click animation
    private static float mMovedPixelToCancel;

    public MonthByWeekAdapter(Context context, HashMap<String, Integer> params, Handler handler) {
        super(context, params);
        Log.w(TAG, "MonthByWeekAdapter: ^**_**^");
        mEventDialogHandler = handler;
        if (params.containsKey(WEEK_PARAMS_IS_MINI)) {
            mIsMiniMonth = params.get(WEEK_PARAMS_IS_MINI) != 0;
        }
        mShowAgendaWithMonth = Utils.getConfigBool(context, R.bool.show_agenda_with_month);
        ViewConfiguration vc = ViewConfiguration.get(context);
        mOnDownDelay = ViewConfiguration.getTapTimeout();
        mMovedPixelToCancel = vc.getScaledTouchSlop();
        mTotalClickDelay = mOnDownDelay + mOnTapDelay;
    }

    public void animateToday() {
        mAnimateToday = true;
        mAnimateTime = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        Log.w(TAG, "init: ^**_**^");
        super.init();
        mGestureDetector = new GestureDetector(mContext, new CalendarGestureListener());
        mController = CalendarController.getInstance(mContext);
        mHomeTimeZone = Utils.getTimeZone(mContext, null);
        mSelectedDay.switchTimezone(mHomeTimeZone);
        mToday = new Time(mHomeTimeZone);
        mToday.setToNow();
        mTempTime = new Time(mHomeTimeZone);
    }

    private void updateTimeZones() {
        Log.w(TAG, "updateTimeZones: ^**_**^");
        mSelectedDay.timezone = mHomeTimeZone;
        mSelectedDay.normalize(true);
        mToday.timezone = mHomeTimeZone;
        mToday.setToNow();
        mTempTime.switchTimezone(mHomeTimeZone);
    }

    @Override
    public void setSelectedDay(Time selectedTime) {
        mSelectedDay.set(selectedTime);
        long millis = mSelectedDay.normalize(true);
        mSelectedWeek = Utils.getWeeksSinceEpochFromJulianDay(
                Time.getJulianDay(millis, mSelectedDay.gmtoff), mFirstDayOfWeek);//根据时间和周的第一天,计算时间是儒略历的第几周
        Log.w(TAG, "setSelectedDay: ^**_**^,选中第mSelectedWeek:" + mSelectedWeek + "周,selectedTime:" + selectedTime);

        notifyDataSetChanged();
    }

    /**
     *
     * @param firstJulianDay 查询的第一天儒略日数
     * @param numDays  总共查询的天数
     * @param events   日程总数
     */
    public void setEvents(int firstJulianDay, int numDays, ArrayList<Event> events) {
        Log.w(TAG, "setEvents:: ^**_**^,第一天儒略日数firstJulianDay:" + firstJulianDay + ",总共天数numDays:" + numDays + ",events size:" + events.size());
        if (mIsMiniMonth) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "Attempted to set events for mini view. Events only supported in full"
                        + " view.");
            }
            return;
        }
        mEvents = events;//日程总数
        mFirstJulianDay = firstJulianDay;//查询的第一天儒略日数
        mQueryDays = numDays;//总共查询的天数
        // Create a new list, this is necessary since the weeks are referencing
        // pieces of the old list
        ArrayList<ArrayList<Event>> eventDayList = new ArrayList<ArrayList<Event>>();//创建一个封装每天日程集合
        for (int i = 0; i < numDays; i++) {
            eventDayList.add(new ArrayList<Event>());//57天,每天有n个日程
        }

        if (events == null || events.size() == 0) {
            if(Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "No events. Returning early--go schedule something fun.");
            }
            mEventDayList = eventDayList;
            refresh();
            return;
        }

        //计算每天日程
        // Compute the new set of days with events
        for (Event event : events) {
            int startDay = event.startDay - mFirstJulianDay;//距离第一天日程开始
            int endDay = event.endDay - mFirstJulianDay + 1;//距离第一天日程结束
            Log.d(TAG, "setEvents:: startDay:" + startDay + ",endDay:" + endDay + ",numDays:" + numDays);
            if (startDay < numDays || endDay >= 0) {
                if (startDay < 0) {
                    startDay = 0;//日程开始时间小于查询第一天,从第一天开始日程
                }
                if (startDay > numDays) {
                    continue;//日程开始时间超过查询总天数,跳过
                }
                if (endDay < 0) {//日程结束时间小于查询开始日期,跳过
                    continue;
                }
                if (endDay > numDays) {//日程结束时间超过查询总天数,赋值日程结束时间是查询总天数
                    endDay = numDays;
                }
                Log.d(TAG, "setEvents:: add everyday enents,startDay:" + startDay + ",endDay:" + endDay + ",numDays:" + numDays);
                for (int j = startDay; j < endDay; j++) {
                    Log.d(TAG, "setEvents:: 设置" + j + "天日程数");
                    event.dump();
                    eventDayList.get(j).add(event);//设置每天日程数
                }
            }
        }
        if(Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Processed " + events.size() + " events.");
        }
        mEventDayList = eventDayList;
        Log.d(TAG, "setEvents:: mEventDayList:" + mEventDayList.size());
        refresh();
    }

    @SuppressWarnings("unchecked")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Log.w(TAG, "getView: ^**_**^,position:" + position + ",mIsMiniMonth:" + mIsMiniMonth + ",mAnimateToday:" + mAnimateToday + ",mSelectedWeek:" + mSelectedWeek);
        if (mIsMiniMonth) {
            return super.getView(position, convertView, parent);
        }
        MonthWeekEventsView v;
        LayoutParams params = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        HashMap<String, Integer> drawingParams = null;
        boolean isAnimatingToday = false;
        if (convertView != null) {
            v = (MonthWeekEventsView) convertView;
            // Checking updateToday uses the current params instead of the new
            // params, so this is assuming the view is relatively stable
            if (mAnimateToday && v.updateToday(mSelectedDay.timezone)) {
                long currentTime = System.currentTimeMillis();
                // If it's been too long since we tried to start the animation
                // don't show it. This can happen if the user stops a scroll
                // before reaching today.
                if (currentTime - mAnimateTime > ANIMATE_TODAY_TIMEOUT) {
                    mAnimateToday = false;
                    mAnimateTime = 0;
                } else {
                    isAnimatingToday = true;
                    // There is a bug that causes invalidates to not work some
                    // of the time unless we recreate the view.
                    v = new MonthWeekEventsView(mContext);
               }
            } else {
                drawingParams = (HashMap<String, Integer>) v.getTag();
            }
        } else {
            v = new MonthWeekEventsView(mContext);
        }
        if (drawingParams == null) {
            drawingParams = new HashMap<String, Integer>();
        }
        drawingParams.clear();

        v.setLayoutParams(params);
        v.setClickable(true);
        v.setOnTouchListener(this);

        int selectedDay = -1;
        if (mSelectedWeek == position) {
            selectedDay = mSelectedDay.weekDay;
            Log.d(TAG, "getView:: selectedDay:" + selectedDay);//选中本周的周几
        }

        Log.d(TAG, "getView:: " + position + ",selectedDay:" + selectedDay + ",mShowWeekNumber:" + mShowWeekNumber + ",mFirstDayOfWeek:" + mFirstDayOfWeek + ",mDaysPerWeek:" + mDaysPerWeek + ",mFocusMonth:" + (mFocusMonth + 1) + ",mOrientation:" + mOrientation);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_HEIGHT,
                (parent.getHeight() + parent.getTop()) / mNumWeeks);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_SELECTED_DAY, selectedDay);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_SHOW_WK_NUM, mShowWeekNumber ? 1 : 0);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_WEEK_START, mFirstDayOfWeek);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_NUM_DAYS, mDaysPerWeek);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_WEEK, position);
        drawingParams.put(SimpleWeekView.VIEW_PARAMS_FOCUS_MONTH, mFocusMonth);
        drawingParams.put(MonthWeekEventsView.VIEW_PARAMS_ORIENTATION, mOrientation);

        if (isAnimatingToday) {
            drawingParams.put(MonthWeekEventsView.VIEW_PARAMS_ANIMATE_TODAY, 1);
            mAnimateToday = false;
        }

        v.setWeekParams(drawingParams, mSelectedDay.timezone);
        sendEventsToView(v);
        return v;
    }

    private void sendEventsToView(MonthWeekEventsView v) {
        Log.w(TAG, "sendEventsToView: ^**_**^,mEventDayList.size():" + mEventDayList.size());

        if (mEventDayList.size() == 0) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "No events loaded, did not pass any events to view.");
            }
            Log.d(TAG, "sendEventsToView:: No events loaded, did not pass any events to view.");
            v.setEvents(null, null);
            return;
        }
        int viewJulianDay = v.getFirstJulianDay();
        int start = viewJulianDay - mFirstJulianDay;
        int end = start + v.mNumDays;
        if (start < 0 || end > mEventDayList.size()) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Week is outside range of loaded events. viewStart: " + viewJulianDay
                        + " eventsStart: " + mFirstJulianDay);
            }
            Log.d(TAG, "sendEventsToView:: Week is outside range of loaded events. viewStart: " + viewJulianDay
                    + " eventsStart: " + mFirstJulianDay);
            v.setEvents(null, null);
            return;
        }
        Log.d(TAG, "sendEventsToView:: start:" + start + ",end:" + end + ",mEvents:" + mEvents.size());
        v.setEvents(mEventDayList.subList(start, end), mEvents);//设置每个条目一周的日程
    }

    @Override
    protected void refresh() {
        Log.w(TAG, "refresh: ^**_**^ ");
        mFirstDayOfWeek = Utils.getFirstDayOfWeek(mContext);
        mShowWeekNumber = Utils.getShowWeekNumber(mContext);
        mHomeTimeZone = Utils.getTimeZone(mContext, null);
        mOrientation = mContext.getResources().getConfiguration().orientation;
        Log.d(TAG, "refresh:: mFirstDayOfWeek:" + mFirstDayOfWeek + ",mHomeTimeZone:" + mHomeTimeZone);//周的第一天
        updateTimeZones();
        notifyDataSetChanged();
    }

    @Override
    protected void onDayTapped(Time day) {
        Log.w(TAG, "onDayTapped: ^**_**^ ");
        setDayParameters(day);
         if (mShowAgendaWithMonth || mIsMiniMonth) {
            // If agenda view is visible with month view , refresh the views
            // with the selected day's info
            mController.sendEvent(mContext, EventType.GO_TO, day, day, -1,
                    ViewType.CURRENT, CalendarController.EXTRA_GOTO_DATE, null, null);
        } else {
            // Else , switch to the detailed view
            mController.sendEvent(mContext, EventType.GO_TO, day, day, -1,
                    ViewType.DETAIL,
                            CalendarController.EXTRA_GOTO_DATE
                            | CalendarController.EXTRA_GOTO_BACK_TO_PREVIOUS, null, null);
        }
    }

    private void setDayParameters(Time day) {
        day.timezone = mHomeTimeZone;
        Time currTime = new Time(mHomeTimeZone);
        currTime.set(mController.getTime());
        day.hour = currTime.hour;
        day.minute = currTime.minute;
        day.allDay = false;
        day.normalize(true);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Log.w(TAG, "onTouch: ^**_**^ ");
        if (!(v instanceof MonthWeekEventsView)) {
            return super.onTouch(v, event);
        }

        int action = event.getAction();

        // Event was tapped - switch to the detailed view making sure the click animation
        // is done first.
        if (mGestureDetector.onTouchEvent(event)) {
            mSingleTapUpView = (MonthWeekEventsView) v;
            long delay = System.currentTimeMillis() - mClickTime;
            // Make sure the animation is visible for at least mOnTapDelay - mOnDownDelay ms
            mListView.postDelayed(mDoSingleTapUp,
                    delay > mTotalClickDelay ? 0 : mTotalClickDelay - delay);
            return true;
        } else {
            // Animate a click - on down: show the selected day in the "clicked" color.
            // On Up/scroll/move/cancel: hide the "clicked" color.
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mClickedView = (MonthWeekEventsView)v;
                    mClickedXLocation = event.getX();
                    mClickTime = System.currentTimeMillis();
                    mListView.postDelayed(mDoClick, mOnDownDelay);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_SCROLL:
                case MotionEvent.ACTION_CANCEL:
                    clearClickedView((MonthWeekEventsView)v);
                    break;
                case MotionEvent.ACTION_MOVE:
                    // No need to cancel on vertical movement, ACTION_SCROLL will do that.
                    if (Math.abs(event.getX() - mClickedXLocation) > mMovedPixelToCancel) {
                        clearClickedView((MonthWeekEventsView)v);
                    }
                    break;
                default:
                    break;
            }
        }
        // Do not tell the frameworks we consumed the touch action so that fling actions can be
        // processed by the fragment.
        return false;
    }

    /**
     * This is here so we can identify events and process them
     */
    protected class CalendarGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            Log.w(TAG, "onLongPress: ^**_**^ ");
            if (mLongClickedView != null) {
                Time day = mLongClickedView.getDayFromLocation(mClickedXLocation);
                if (day != null) {
                    mLongClickedView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    Message message = new Message();
                    message.obj = day;
                    mEventDialogHandler.sendMessage(message);
                }
                mLongClickedView.clearClickedDay();
                mLongClickedView = null;
             }
        }
    }

    // Clear the visual cues of the click animation and related running code.
    private void clearClickedView(MonthWeekEventsView v) {
        mListView.removeCallbacks(mDoClick);
        synchronized(v) {
            v.clearClickedDay();
        }
        mClickedView = null;
    }

    // Perform the tap animation in a runnable to allow a delay before showing the tap color.
    // This is done to prevent a click animation when a fling is done.
    private final Runnable mDoClick = new Runnable() {
        @Override
        public void run() {
            if (mClickedView != null) {
                synchronized(mClickedView) {
                    mClickedView.setClickedDay(mClickedXLocation);
                }
                mLongClickedView = mClickedView;
                mClickedView = null;
                // This is a workaround , sometimes the top item on the listview doesn't refresh on
                // invalidate, so this forces a re-draw.
                mListView.invalidate();
            }
        }
    };

    // Performs the single tap operation: go to the tapped day.
    // This is done in a runnable to allow the click animation to finish before switching views
    private final Runnable mDoSingleTapUp = new Runnable() {
        @Override
        public void run() {
            if (mSingleTapUpView != null) {
                Time day = mSingleTapUpView.getDayFromLocation(mClickedXLocation);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Touched day at Row=" + mSingleTapUpView.mWeek + " day=" + day.toString());
                }
                if (day != null) {
                    onDayTapped(day);
                }
                clearClickedView(mSingleTapUpView);
                mSingleTapUpView = null;
            }
        }
    };
}
