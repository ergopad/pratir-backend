package tasks

import play.api.inject._

class MintModule extends SimpleModule(bind[MintTask].toSelf.eagerly())