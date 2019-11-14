![Build Status](https://github.com/adsamcik/Tracker-Android/workflows/Android%20CI/badge.svg)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/43cf544eff334ca0a3a15c7791a64e27)](https://www.codacy.com/app/adsamcik/Tracker-Android?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=adsamcik/Tracker-Android&amp;utm_campaign=Badge_Grade)

# Tracker Android (Advention)

Tracker is a free open-source offline location, fitness, Wi-Fi, cell tracker with statistics and game elements. Now that that is out of the way, you can read more in features.

## Features

- Track location, Wi-Fi networks, cell networks, activity and steps (everything can be enabled or disabled)
- Automatically start tracking based on your activity (You can choose between disabled, on foot and in motion options)
- Lock tracking for specified amount of time (proper time control tbd, currently only preset times are supported) or until phone is connected to a charged
- Show your data on the map
- Show you details about your tracking sessions (enabled or manually triggered)
- Fully customizable color of the interface with options for static color, day night switching or smooth morning-day-evening-night transition. Colors for all are fully customizable.
- Add your own custom activities for sessions or edit detected activities for a given session
- Games: Challenges (currently there are only 3 challenges available but more are planned)
- Dynamic modules: Statistics, Game, Map
- Export data to GPX, KML, JSON and Sqlite
- Import data from GPX and batch import from zip
- Supported languages: English, Czech
- Supported length systems: metric, imperial (USC), ancient roman
- Does not upload your tracked data anywhere (well except for automatic Android backup which sometimes works)

## Roadmap

Milestones form a roadmap of sorts for this project. Dates are only approximate and are subject to change. If you want to implement any one feature, see [contributions](#contributions)

## Versioning

For readability it's separeted by spaces and underscores however these are not present it the actual versioning, see example.
YEAR.VERSION RELEASE BUILD_NUMBER

Eg. 2020.1β1

### Releases

New releases will be developed in seperate branches and always have up to date version codes so it is easier to know in what state the release is.

#### Iota ι

Early internal builds after significant changes. Not recommended for use by anyone else but developers. In the future these should be built under different id. These builds might not even have migrations in place.

#### Alpha α

Early test builds usually bringing new features. These builds are not yet feature complete but should have migrations for database and be stable in a way that some basic actions do not crash them every time.

#### Beta β

Late test builds that are feature complete (no new features should be added in these builds, only bugfixes). These builds are meant for general testers that want to test the app but also use it regularly. While there will probably be some issues and crashes, these should be rather sparse and mostly contain edge cases and issues that were not found in the Alpha or Iota builds.

#### Release

Most stable releases meant for general public

## Contributions

Contributions to Tracker are welcome. Planned new features can be seen in milestones. If you want any new feature (even if it's in later milestone or no milestone at all) you are free to do so. It is recommended to consult on larger issues as they might collide with some future system revamps and might not be merged because of it.
