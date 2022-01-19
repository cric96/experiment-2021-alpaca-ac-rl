package it.unibo.scafi.casestudy

import cats.data.NonEmptySet
import it.unibo.alchemist.model.implementations.nodes.SimpleNodeManager
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist.{ID, Metric}
import it.unibo.alchemist.tiggers.EndHandler
import it.unibo.learning.{Q, QLearning}
import it.unibo.scafi.casestudy.CrfLikeDefinition.State
import it.unibo.scafi.casestudy.LearningProcess.Trajectory

import scala.jdk.CollectionConverters.IteratorHasAsScala

class SwapSourceOnline extends SwapSourceLike {
  // Constants
  val maxValue = 5
  val maxDiff = 100
  val maxUpdateVelocity = 2
  val hopCountMetric: Metric = () => 1
  val hopRadius = 1
  val globalReward = -100
  /// Learning definition
  // Plain RL
  lazy val learningAlgorithm: QLearning.Hysteretic[PlainRLDefinition.State, PlainRLDefinition.Action] =
    QLearning.Hysteretic(actions, alpha.value(episode), beta.value(episode), gamma)
  lazy val windowDifferenceSize: Int = node.get[java.lang.Integer]("window")
  lazy val trajectorySize: Int = node.get[java.lang.Integer]("trajectory")
  // CRF Like RL
  lazy val crfLikeLearning: QLearning.Hysteretic[State, CrfLikeDefinition.Action] =
    QLearning.Hysteretic(
      CrfLikeDefinition.actionSpace(List(2)),
      alpha.value(episode),
      beta.value(episode),
      gamma
    )
  // Alchemist molecules
  lazy val actions: NonEmptySet[PlainRLDefinition.Action] = node.get("actions")
  lazy val radius: Double = node.get("range")
  lazy val shouldLearn: Boolean = learnCondition && !source

  // Aggregate program
  override def aggregateProgram(): Unit = {
    ///// BASELINE
    val classicHopCount = hopGradient(source)
    ///// OPTIMAL REFERENCE
    val hopCountWithoutRightSource =
      hopGradient(mid() == leftSrc) // optimal gradient when RIGHT_SRC stops being a source
    val refHopCount = if (passedTime() >= rightSrcStop) hopCountWithoutRightSource else classicHopCount
    val eps = if (learnCondition) { epsilon.value(episode) }
    else { 0.0 }
    ///// LEARNING PROBLEMS DEFINITION
    // Crf like learning definition
    val crfProblem = learningProcess(GlobalQ.crfLikeQ)
      .stateDefinition(data => crfLikeState(data, maxValue))
      //.rewardDefinition(out => localSignal(out))
      .rewardDefinition(out => rewardSignal(refHopCount, out))
      .actionEffectDefinition((output, _, action) => crfActionEffectLike(output, action))
      .initialConditionDefinition(State(None, None), Double.PositiveInfinity)
    // Old learning definition
    val learningProblem = learningProcess(GlobalQ.standardQ)
      .stateDefinition(plainStateFromWindow)
      //.rewardDefinition(output => localSignal(output))
      .rewardDefinition(out => rewardSignal(refHopCount, out))
      .actionEffectDefinition((output, _, action) => minHoodPlus(nbr(output)) + action + 1)
      .initialConditionDefinition(List.empty, Double.PositiveInfinity)
    // RL Progression
    val (plainLearningResult, trajectory) = learningProblem.step(learningAlgorithm, eps, shouldLearn)
    val (crfLikeLearningResult, crfLikeTrajectory) = crfProblem.step(crfLikeLearning, eps, shouldLearn)
    //// STATE OF THE ART
    val crf = crfGradient(40 / 12.0)(source = source, hopCountMetric)
    val bis = bisGradient(hopRadius)(source, hopCountMetric)
    //// ERROR ESTIMATION COUNT
    val rlBasedError = refHopCount - plainLearningResult.output
    val overEstimate =
      if (rlBasedError > 0) { 1 }
      else { 0 }
    val underEstimate =
      if (rlBasedError < 0) { 1 }
      else { 0 }
    //// DATA STORAGE
    node.put("qtable", plainLearningResult.q)
    node.put("classicHopCount", classicHopCount)
    node.put("reference", refHopCount)
    node.put("rlbasedHopCount", crfLikeLearningResult.output)
    node.put("oldRlBased", plainLearningResult.output)
    node.put(s"passed_time", passedTime())
    node.put("src", source)
    node.put("action", plainLearningResult.action)
    node.put("oldTrajectory", trajectory)
    node.put("crfTrajectory", crfLikeTrajectory)
    node.put("overestimate", overEstimate)
    node.put("underestimate", underEstimate)
    node.put(s"err_classicHopCount", outputEvaluation(refHopCount, classicHopCount))
    node.put(s"err_rlbasedHopCount", outputEvaluation(refHopCount, crfLikeLearningResult.output))
    node.put(s"err_oldRl", outputEvaluation(refHopCount, plainLearningResult.output))
    node.put(s"err_crf", outputEvaluation(refHopCount.toInt, crf.toInt))
    node.put(s"err_bis", outputEvaluation(refHopCount.toInt, bis.toInt))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Any")) // because of unsafe scala binding
  override lazy val endHandler: EndHandler[_] = {
    val storeMonitor = new EndHandler[Any](
      sharedLogic = () => {},
      leaderLogic = () => {
        println(s"Agents learn? ${learnCondition.toString}")
        println(s"Episodes: ${episode.toString}")
        println(s"Epsilon: ${epsilon.value(episode).toString}")
        val nodes = alchemistEnvironment.getNodes.iterator().asScala.toList.map(node => new SimpleNodeManager(node))
        val data = {
          nodes.map { node =>
            (
              node.get[Trajectory[PlainRLDefinition.State, PlainRLDefinition.Action]]("oldTrajectory"),
              node.get[java.lang.Double]("reference"),
              node.get[java.lang.Double]("oldRlBased")
            )
          }
        }
        //data.filter(_ => learnCondition).foreach { case (trj, ref, out) =>
        //  globalSignal(trj, GlobalQ.standardQ, learningAlgorithm, ref, out)
        //}
      },
      id = mid()
    )
    alchemistEnvironment.getSimulation.addOutputMonitor(storeMonitor)
    storeMonitor
  }

  protected def plainStateFromWindow(output: Double): PlainRLDefinition.State = {
    val minOutput = minHood(nbr(output))
    val recent = recentValues(windowDifferenceSize, minOutput)
    val oldState = recent.headOption.getOrElse(minOutput)
    val diff = (minOutput - oldState) match {
      case diff if Math.abs(diff) > maxDiff => maxDiff * diff.sign
      case diff                             => diff
    }
    recentValues(trajectorySize, diff).toList.map(_.toInt)
  }

  protected def crfLikeState(output: Double, maxValue: Double): CrfLikeDefinition.State = {
    val other = excludingSelf.reifyField(nbr(output))
    val differences = other.map { case (k, v) => k -> (output - v) }
    def align(option: Option[(ID, Double)]): Option[Int] = option
      .map(_._2)
      .map(diff =>
        if (diff.abs > maxValue) { maxValue * diff.sign }
        else { diff }
      )
      .map(_.toInt)

    val left = align(differences.find(_._1 < mid()))
    val right = align(differences.find(_._1 > mid()))
    CrfLikeDefinition.State(left, right)
  }

  protected def crfActionEffectLike(output: Double, action: CrfLikeDefinition.Action): Double = {
    if (action.ignoreLeft && action.ignoreRight) {
      output + action.upVelocity
    } else {
      val data = excludingSelf.reifyField(nbr(output))
      val left = data.find(data => data._1 < mid() && !action.ignoreLeft).map(_._2)
      val right = data.find(data => data._1 > mid() && !action.ignoreRight).map(_._2)
      List(left, right).collect { case Some(data) => data }.minOption.map(_ + 1).getOrElse(output)
    }
  }

  protected def rewardSignal(groundTruth: Double, currentValue: Double): Double =
    if ((groundTruth.toInt - currentValue.toInt) == 0) { 0 }
    else { -1 }

  protected def localSignal(currentValue: Double): Double =
    if (isStable(currentValue, windowDifferenceSize)) { 0 }
    else { -1 }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps")) // because of unsafe scala binding
  protected def globalSignal[S, A](
      trajectory: Trajectory[S, A],
      q: Q[S, A],
      learning: QLearning.Type[S, A],
      lastCorrectValue: Double,
      lastOutput: Double
  ): Q[_, _] = {
    val (lastState, _, _) = trajectory.head
    val (lastMinusOne, action, _) = trajectory.tail.head
    val reward = if (lastCorrectValue.toInt != lastOutput.toInt) { globalReward }
    else { 0 }
    learning.improve((lastMinusOne, action, reward, lastState), q)
  }

  protected def outputEvaluation(ref: Double, value: Double): Double = {
    val result = (ref - value).abs
    if (result.isInfinite) { alchemistEnvironment.getNodes.size() }
    else { result }
  }
}
