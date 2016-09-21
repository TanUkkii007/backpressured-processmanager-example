package tanukkii.backpressured.processmanager.example

import java.util.UUID

case class CommandRequestId(value: String)

object CommandRequestId{
  def random: CommandRequestId = CommandRequestId(UUID.randomUUID().toString)
}