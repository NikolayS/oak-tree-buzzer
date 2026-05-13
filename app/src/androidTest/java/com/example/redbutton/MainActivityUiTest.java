package com.example.redbutton;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.hamcrest.Matchers.allOf;

import android.content.Intent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainActivityUiTest {
    @Test
    public void sendButtonIsVisibleOnLaunch() {
        launchActivityForUiTest();

        onView(withText("Send")).check(matches(isDisplayed()));
        onView(withText("Reset")).check(matches(isDisplayed()));
    }

    @Test
    public void sendButtonStaysVisibleAfterMakingSelections() {
        launchActivityForUiTest();

        onView(withText("Op1")).perform(click());
        onView(withText("Exam")).perform(click());
        onView(withText("Dr. Riad")).perform(click());

        onView(withText("Send")).check(matches(isDisplayed()));
    }

    @Test
    public void senderTabletShowsSentSelectionUntilAcknowledged() {
        launchActivityForUiTest();

        onView(withText("Op1")).perform(click());
        onView(withText("Exam")).perform(click());
        onView(withText("Dr. Riad")).perform(click());
        onView(withText("Send")).perform(click());
        onView(isRoot()).perform(waitForMainThread());

        onView(withText("Op1 ✓")).check(matches(isDisplayed()));
        onView(allOf(withText("Exam"), isDisplayed())).check(matches(isDisplayed()));
        onView(allOf(withText("Dr. Riad"), isDisplayed())).check(matches(isDisplayed()));
        onView(withText("Send")).check(matches(isDisplayed()));
    }

    private static ActivityScenario<MainActivity> launchActivityForUiTest() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_DISABLE_BACKGROUND_WORK, true);
        return ActivityScenario.launch(intent);
    }

    private static ViewAction waitForMainThread() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isRoot();
            }

            @Override
            public String getDescription() {
                return "wait for pending UI work";
            }

            @Override
            public void perform(UiController uiController, View view) {
                uiController.loopMainThreadUntilIdle();
            }
        };
    }
}
