package it.unibo.scafi.casestudy

import cats.data.NonEmptySet
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist._
import it.unibo.learning.{Clock, Q, QLearning, TimeVariable}
import scala.util.Random
class AggregateProgramCheck
    extends AggregateProgram
    with HopCountLearning
    with StandardSensors
    with ScafiAlchemistSupport
    with Gradients {
  override def source: Boolean = mid() == 0
  implicit lazy val rnd: Random = randomGen
  lazy val learn: Boolean = node.get[java.lang.Boolean]("learn")
  lazy val actions: NonEmptySet[Int] = NonEmptySet.of(10)
  lazy val qLearning: QLearning.Type[Int, Int] = QLearning.Plain[Int, Int](actions, TimeVariable.independent(0.1), 0.9)
  override def main(): Any = {
    val learningProblem = learningProcess(Q.zeros[Int, Int]())
      .stateDefinition(a => a.toInt)
      .rewardDefinition(a => a) //fake reward
      .actionEffectDefinition((out, a) => a) //for testing it return the action
      .initialConditionDefinition(0, Double.PositiveInfinity)
    val (result, _) = if (learn) {
      learningProblem.learn(qLearning, TimeVariable.independent(0.1), Clock.start)
    } else {
      learningProblem.actGreedy(qLearning, Clock.start)
    }
    node.put("q", result.q)
    node.put("clock", result.clock)
    node.put("output", result.output)
  }
}
