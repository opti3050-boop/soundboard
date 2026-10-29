package com.soundboard;

import java.util.ArrayList;
import java.util.List;

/** Top-level object written to / read from the .json save file. */
public class LayoutData {

    public AppSettings      settings = new AppSettings();
    public List<TabData>    tabs     = new ArrayList<>();

    public static class TabData {
        public String           id      = "";    // persistent UUID for cache folder
        public String           name    = "Page";
        public List<ButtonData> buttons = new ArrayList<>();
    }
}
