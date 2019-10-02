[![Build Status](https://travis-ci.org/adsamcik/Tracker-Android.svg?branch=master)](https://travis-ci.org/adsamcik/Tracker-Android)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/43cf544eff334ca0a3a15c7791a64e27)](https://www.codacy.com/app/adsamcik/Tracker-Android?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=adsamcik/Tracker-Android&amp;utm_campaign=Badge_Grade)

# Tracker Android (dev name, brand name reveal soon)

Signals is a free open-source offline location, fitness, Wi-Fi, cell tracker with statistics and game elements. Now that that is out of the way, you can read more in features.

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

## Planned features

- Database import
- Proper automatic database backup (Android automatic only goes up to 25 megabytes)
- More games
- More challenges
- More statistics (pace)
- And more (roadmaps are in issues)

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

Signals is open to contributions, if you want to improve some part of the app or add some features feel free to do so. Each contribution should not create any issues. If an issue is not valid, it should be explicitly stated as suppressed. However every pull request is manually verified so overly using Suppress annotation will be seen as issue in itself.
