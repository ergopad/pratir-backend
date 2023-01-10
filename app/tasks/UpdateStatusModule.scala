package tasks

import play.api.inject.SimpleModule
import play.api.inject._

class UpdateStatusModule extends SimpleModule(bind[UpdateStatusTask].toSelf.eagerly())