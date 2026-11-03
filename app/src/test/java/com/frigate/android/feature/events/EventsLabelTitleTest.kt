package com.frigate.android.feature.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventsLabelTitleTest {
    @Test fun labelStartsWithCapitalLikeTheMockup() {
        assertEquals("Person", eventsLabelTitle("person"))
        assertEquals("Car", eventsLabelTitle("car"))
    }

    @Test fun alreadyCapitalisedAndEmptyStayAsTheyAre() {
        assertEquals("Amazon", eventsLabelTitle("Amazon"))
        assertEquals("", eventsLabelTitle(""))
    }

    @Test fun onlyTheFirstLetterChanges() {
        assertEquals("Face_recognized", eventsLabelTitle("face_recognized"))
    }
}
