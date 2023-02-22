package tasks

import play.api.inject._

class StartupModule extends SimpleModule(bind[StartupTask].toSelf.eagerly())
