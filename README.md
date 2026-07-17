# Arcturus Morningstar #

This emulator has been updated to match Habbos Wired. I will start by saying this was used more so as a sandbox so i would consider it NOT PROD READY. Again, this came from the 4.0.0  version where they state it as a dev build also.

All of the Wired was tested as it was being built. I tested it against my own high Wired knowledge with Habbo and lots of docs I've saved over time. Even small details like one-way-gate logic in pair with AvatarToFurni effect which is highly used in mazing, to creating quadratic formula bounce with context variables.

NOTE: Everything for the most part 'works' but there are small things you will most likely find that don't. The reason being is I haven't had the time to actually create large scale system games to test the network of 100+ boxes.

I'm releasing this to help people look into the new wired and properly bring it to their hotel, so again treat this as a sandbox, if you find a problem you can fix it.

IMPORTANT: This must be used in pair with the [nitro-react-Wired](https://github.com/iSetht/nitro-react-Wired) as it contains all the client side stuff, and you will have to update the database with a few updates found in the sql folder for things like variables, chests, etc.

Things that exist that you may wonder (Did he add this?):
- Chests
- All chest wired
- A fully finished :wired menu
- Temp furniture
- Movement Curve Add-on
- Projectile Add-on
- Variables

Custom Features I added that should be mentioned:
- Tile Picks Selector (Allows user to select specific tiles to use with wired)
- Movement Curve Plus (Introduces X,Y and Bounce curves using easing functions (Habbo only has quadratic Z currently)
- Show Message Plus (Introduces a styling option to ShowMessage box, allows for text rich formatting and ease of building nicer GUI bubbles)
- @has_rights added as an editable internal variables (Give/Remove rights with wired)
- Repeater speeds use proper movement ticks with no AnimationTime add-on, let it be known though you can still add AnimationTime and adjust speeds for different results
- Updated Background Toner
- @is_holding_down and @held_down internal variables added alongside USER_RELEASES trigger. @is_holding_down is an internal variable created when a user holds down their mouse button, it tracks what object they held down on, coordinates, duration_ticks. @held_down is a context variable created only on the release of mouse button and to be used with USER_RELEASES which gives users the total_duration_ticks, origin of held down click and other options (This basically allows you to create systems where a user must hold down their mouse to do something)
- Updated Leaderboard. Added Gold/Silver/Bronze color to top player, added ability to 'x' users and remove off board, added an all new Leaderboard View that displays the top 3 users and their render on pillars with score and name

Arcturus Morningstar is as a fork of Arcturus Emulator by TheGeneral. Arcturus Morningstar is released under the [GNU General Public License v3](https://www.gnu.org/licenses/gpl-3.0.txt) and is developed for free by talented developers at Krews.org and is compatible with the following client revision/community projects:


| Flash | Community Clients |
| ------------- | ------------- |
| [PRODUCTION-201611291003-338511768](https://git.krews.org/morningstar/apollyon/uploads/dc669a26613bf2356e48eb653734ab29/patched-habbo.swf) | [Nitro (Recommended)*](https://git.krews.org/nitro) |
 
###### *Note to use Nitro you will need to use the following [plugin](https://git.krews.org/nitro/ms-websockets/-/releases) with Arcturus Morningstar #######





[![image](https://img.shields.io/discord/557240155040251905?style=for-the-badge&logo=discord&color=7289DA&label=KREWS&logoColor=fff)](https://discord.gg/BzfFsTp)

## Download ##
[![image](https://img.shields.io/badge/STABLE%20RELEASES-3.5.4-success.svg?style=for-the-badge&logo=appveyor)](https://git.krews.org/morningstar/Arcturus-Community/-/releases)

[![image](https://img.shields.io/badge/DEVELOPER%20BUILDS-4.0-red.svg?style=for-the-badge&logo=appveyor)](https://git.krews.org/morningstar/Arcturus-Community/-/jobs) *

[![image](https://img.shields.io/badge/RECOMMENDED%20PLUGINS-blue.svg?style=for-the-badge&logo=)](https://git.krews.org/morningstar/archive) 

###### *Note: MS 4.0 is expected to have changes to the Plugin API, backwards compatibility with Plugins is dependant on the plugin developer.  #######


### Branches ###
There are two main branches in use on the Arcturus Morningstar git. Developers should target the dev branch for merge requests.

| master * | The stable 3.x branch of Arcturus Morningstar. |
|----------|------------------------------------------------|
###### * Note: This branch is no longer being maintained except for Security Patches #######

| dev* | The dev branch of Arcturus Morningstar. |
|------|-----------------------------------------|
###### * Note: This version is currently untested on a production hotel and is not recommended for daily use until a release has been made. #######




There is no set timeframe on when new versions will be released or when the stable branch will be updated


## Can I Help!? ##
#### Reporting Bugs: ####
You can report problems via the [Issue Tracker](https://git.krews.org/morningstar/Arcturus-Community/issues)*
###### * When making an bug report or a feature request use the template we provide so that it can be categorized correctly and we have more information to replicate a bug or implement a feature correctly. ######
#### Can I contribute code to this project? ####
Of Course! Please target the developer branch if you have fixed a bug from the git, and feel free to do a [merge request](https://git.krews.org/morningstar/Arcturus-Community/issues)*
###### * Anyone is allowed to fork the project and make pull requests, we make no guarantee that pull requests will be approved into the project. Please Do NOT push code which does not replicate behaviour on habbo.com, instead make the behaviour configurable or as a plugin. ######



## Plugin System ##
The robust Plugin System included in the original Arcturus release is also included in Arcturus Morningstar, if you're interested in making your own plugins, feel free to ask around on our discord and we'll point you in the right direction! 

A lot of the community aren't used to modifying things in this way, so we've written a few pros:
1. Other people will see that plugins are the normal way of adding custom features
2. Plugins can be added and removed at the hotel owner's choice, it makes customizing the hotel easier
3. Developers will be able to read plugin source code to learn how to make their own plugins, without the need to look in complicated source code

## Making money ##
We have no problem with developers making money through the sale of custom features, plugins or maintenance work.

Sale of a special edition of a *source code* will not be permitted. You may use your own private edition of a source code, but we will not help you if you have any problems with it.

If we ever are to make paid features or plugins, we will not prevent or discourage developers from creating alternative options for users.






### Credits ###
    
       - TheGeneral (Arcturus Emulator)
       - Beny 
       - Alejandro
       - Capheus
       - Skeletor
       - Harmonic
       - Mike
       - Remco
       - zGrav
       - Quadral
       - Harmony
       - Swirny
       - ArpyAge
       - Mikkel
       - Rodolfo
       - Rasmus
       - Kitt Mustang
       - Snaiker
       - nttzx
       - necmi
       - Dome
       - Jose Flores
       - Cam
       - Oliver
       - Narzo
       - Tenshie
       - MartenM
       - Ridge
       - SenpaiDipper
       - Thijmen
       - Brenoepic
       - Stankman
       - Laynester

    



