# Java 8 Stream Optimization Refactorings
[![Build Status](https://travis-ci.com/ponder-lab/Java-8-Stream-Refactoring.svg?token=ysqq4ZuxzD688KNytWSA&branch=master)](https://travis-ci.com/ponder-lab/Java-8-Stream-Refactoring) [![Coverage Status](https://coveralls.io/repos/github/ponder-lab/Java-8-Stream-Refactoring/badge.svg?branch=master&t=mM9zgy)](https://coveralls.io/github/ponder-lab/Java-8-Stream-Refactoring?branch=master) [![GitHub license](https://img.shields.io/badge/license-Eclipse-blue.svg)](https://github.com/khatchadourian-lab/Java-8-Stream-Refactoring/raw/master/LICENSE.txt)

## Screenshot

## Introduction

This prototype refactoring plug-in for [Eclipse](http://eclipse.org) represents ongoing work in developing an automated refactoring tool that would assist developers in optimizing their Java 8 stream client code.

## Usage

### Installation for Usage

### Marking Entry Points

Explicit entry points may be marked using the appropriate annotation found in the corresponding [annotation library][annotations].

### Limitations

There are currently some limitations with embedded streams (i.e., streams declared as part of lambda expressions sent as arguments to intermediate stream operations). This is due to model differences between the Eclipse JDT and WALA. See [#155](https://github.com/ponder-lab/Java-8-Stream-Refactoring/issues/155) for details.

## Contributing

Please see [the wiki](http://github.com/ponder-lab/Java-8-Stream-Refactoring/wiki) for more information regarding development.

### Installation for Development

The project includes a maven configuration file using the tycho plug-in, which is part of the [maven eclipse plugin](http://www.eclipse.org/m2e/). Running `mvn install` will install *most* dependencies. Note that if you are not using maven, this plugin depends on https://github.com/khatchad/edu.cuny.citytech.refactoring.common, the **Eclipse SDK**, **Eclipse SDK tests**, and the **Eclipse testing framework**. The latter three can be installed from the "Install New Software..." menu option under "Help" in Eclipse.

#### Dependencies

You should have the following projects in your workspace:

1. [WALA stream branch](https://github.com/ponder-lab/WALA/tree/streams). Though, not all projecst are necessary. You can close thee ones related to JavaScript and Android.
1. [SAFE](https://github.com/ponder-lab/safe).
1. [Eclipse Java Refactoring Framework](https://github.com/ponder-lab/Common-Eclipse-Java-Refactoring-Framework).

### Running the Evaluator

[annotations]: https://github.com/ponder-lab/edu.cuny.hunter.streamrefactoring.annotations
