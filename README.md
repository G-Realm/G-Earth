# G-Earth

Feature-rich habbo packet logger and manipulator for Windows, Linux and Mac.  
Click here to [download](https://github.com/G-Realm/G-Earth/releases/latest) the latest release.

[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/G-Realm/G-Earth/build.yml?style=flat-square)](https://github.com/G-Realm/G-Earth/actions/workflows/build.yml)
[![Discord](https://img.shields.io/discord/744927320871010404?style=flat-square&label=Discord&color=%237289da)](https://discord.gg/AVkcF8y)

# Support

|         | Windows | MacOS | Linux |
|---------|:-------:|:-----:|:-----:|
| Flash   |    ✅    |   ❌   |   ❌   |
| Origins |    ✅    |  ⚠️   |   ❌   |
| Unity   |    ✅    |   ❌   |   ❌   |
| Nitro   |    ✅    | ️⚠️️  |   ❌   |

The MacOS builds are currently broken.

# Features

- Log outgoing and incoming packets
- Inject, block and replace packets on the fly
- Automatic packet expression prediction
- Auto-detect hotel
- Advanced scheduler
- Advanced extension support
- Python scripting on the fly
- SOCKS proxy
- Identify packet headers through [sulek.dev](https://www.sulek.dev)

# Extensions

Interested in creating your own extension? Check one of the frameworks: 

| Name                       | Language   | Developers   | Github                                                   | Maintained |
|----------------------------|------------|--------------|----------------------------------------------------------|------------|
| G-Earth (Native)           | Java       | sirjonasxx   | https://github.com/G-Realm/G-Earth/tree/moon/G-Earth-Api | ✅          |
| G-Python<sup>1</sup>       | Python     | sirjonasxx   | https://github.com/sirjonasxx/G-Python                   | ✅          |
| Xabbo                      | C#         | b7           | https://github.com/xabbo/gearth                          | ❌          |
| Xabbo scripter<sup>2</sup> | C#         | b7           | https://github.com/xabbo/scripter                        | ❌          |
| G-Node                     | Node.js    | WiredSpast   | https://github.com/WiredSpast/G-Node                     | ✅          |
| G-Rust                     | Rust       | WiredSpast   | https://github.com/WiredSpast/G-Rust                     | ✅          |
| GProgrammer<sup>3</sup>    | Javascript | at15four2020 | https://github.com/at15four2020/GProgrammer/wiki         | ❌          |

<sub>1: built-in in G-Earth through the [live scripting console](https://github.com/sirjonasxx/G-Earth/wiki/G-Python-qtConsole) </sub>  
<sub>2: not an implementation of the extension API, but allows for C# scripting </sub>  
<sub>3: not an implementation of the extension API, but allows for Javascript scripting, also check [G-WiredFly](https://github.com/at15four2020/G-Wiredfy) </sub>

Release your extensions in the [G-ExtensionStore](https://github.com/sirjonasxx/G-ExtensionStore)
