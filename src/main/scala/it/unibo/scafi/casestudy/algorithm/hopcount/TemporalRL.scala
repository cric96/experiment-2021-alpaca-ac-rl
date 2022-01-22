package it.unibo.scafi.casestudy.algorithm.hopcount

import cats.data.NonEmptySet
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist._
import it.unibo.learning.Q.MutableQ
import it.unibo.learning.{Q, QLearning}
import it.unibo.scafi.casestudy.algorithm.RLLike
import it.unibo.scafi.casestudy.algorithm.RLLike.AlgorithmHyperparameter
import it.unibo.scafi.casestudy.algorithm.hopcount.TemporalRL.{Action, State}
import it.unibo.scafi.casestudy.{GradientLikeLearning, TemporalStateManagement}
import scala.util.Random

/** Hop Count RL used in the alpaca submission. The state is encoded as the history of the difference with local node
  * output and the minimum node output. For instance:
  *
  * 0 --- 1 --- 2 --- 3
  *
  * 1 --- 2 --- 3 --- 4
  *
  * 2 --- 3 --- 4 --- 5
  *
  * The state in the node 0 (leftmost) is: -1, -1, -1
  *
  * The action it is simply a delta correction (i.e. how much the local node should increase according the neighbourhood
  * output)
  */
trait TemporalRL extends RLLike {
  self: AggregateProgram with TemporalStateManagement with GradientLikeLearning with ScafiAlchemistSupport =>
  class TemporalRLAlgorithm(
      parameter: AlgorithmHyperparameter,
      actionSet: NonEmptySet[Action],
      maxDiff: Int,
      windowDifferenceSize: Int,
      trajectorySize: Int
  )(implicit rand: Random)
      extends AlgorithmTemplate[State, Action] {
    override val name: String = "temporalRL"
    override protected def learning: QLearning.Type[State, Action] =
      QLearning.Hysteretic[State, Action](actionSet, parameter.alpha, parameter.beta, parameter.gamma)

    override protected def state(output: Double, action: Action): State = {
      val minOutput = minHood(nbr(output))
      val recent = recentValues(windowDifferenceSize, minOutput)
      val oldState = recent.headOption.getOrElse(minOutput)
      val diff = (minOutput - oldState) match {
        case diff if Math.abs(diff) > maxDiff => maxDiff * diff.sign
        case diff                             => diff
      }
      recentValues(trajectorySize, diff).toList.map(_.toInt)
    }

    override protected def actionEffect(oldOutput: Double, state: State, action: Action): Double =
      minHoodPlus(nbr(oldOutput)) + action + 1

    override protected def initialState: State = List.empty

    override protected def q: Q[State, Action] = TemporalRL.q
  }
}

object TemporalRL {
  type State = List[Int]
  type Action = Int
  val q = MutableQ[State, Action](Map.empty).withDefault(0.0)
}