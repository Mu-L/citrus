package com.github.yiuman.citrus.workflow.service.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.github.yiuman.citrus.support.utils.LambdaUtils;
import com.github.yiuman.citrus.support.utils.SpringUtils;
import com.github.yiuman.citrus.workflow.cmd.JumpTaskCmd;
import com.github.yiuman.citrus.workflow.exception.WorkflowException;
import com.github.yiuman.citrus.workflow.model.ProcessPersonalModel;
import com.github.yiuman.citrus.workflow.model.StartProcessModel;
import com.github.yiuman.citrus.workflow.model.TaskCompleteModel;
import com.github.yiuman.citrus.workflow.model.impl.TaskCompleteModelImpl;
import com.github.yiuman.citrus.workflow.model.impl.WorkflowContextImpl;
import com.github.yiuman.citrus.workflow.resolver.TaskCandidateResolver;
import com.github.yiuman.citrus.workflow.service.WorkflowEngineGetter;
import com.github.yiuman.citrus.workflow.service.WorkflowService;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.UserTask;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * 流程处理抽象类<br/>
 * 开启流程、完成任务、签收任务、挂起、激活
 *
 * @author yiuman
 * @date 2020/12/11
 */
public abstract class BaseWorkflowService implements WorkflowService {

    private WorkflowEngineGetter workflowEngineGetter;

    private TaskCandidateResolver taskCandidateResolver;

    @Override
    public ProcessEngine getProcessEngine() {
        workflowEngineGetter = Optional.ofNullable(workflowEngineGetter)
                .orElse(SpringUtils.getBean(WorkflowEngineGetterImpl.class, true));
        return workflowEngineGetter.getProcessEngine();
    }

    public TaskCandidateResolver getTaskCandidateResolver() {
        return taskCandidateResolver = Optional.ofNullable(taskCandidateResolver)
                .orElse(SpringUtils.getBean(TaskCandidateResolver.class, true));
    }

    @Override
    public ProcessInstance starProcess(StartProcessModel model) {
        String processDefineId = model.getProcessDefineKey();
        //找到流程定义
        ProcessDefinition definition = Optional.ofNullable(
                getProcessEngine().getRepositoryService()
                        .createProcessDefinitionQuery()
                        .processDefinitionKey(processDefineId)
                        .latestVersion()
                        .singleResult()
        ).orElseThrow(() -> new IllegalArgumentException(String.format("can not find ProcessDefinition for key:[%s]", processDefineId)));
        //开起流程
        Map<String, Object> processInstanceVars = model.getVariables();
        ProcessInstance processInstance = getProcessEngine().getRuntimeService().startProcessInstanceById(
                definition.getId(),
                model.getBusinessKey(),
                processInstanceVars
        );
        //1.找到当前流程的任务节点。
        //2.若任务处理人与申请人一致，则自动完成任务，直接进入下一步
        //如请假申请为流程的第一步，则此任务自动完成
        if (StringUtils.isNotBlank(model.getUserId())) {
            TaskService taskService = getProcessEngine().getTaskService();
            Task applyUserTask = taskService.createTaskQuery()
                    .processInstanceId(processInstance.getId())
                    .taskCandidateOrAssigned(model.getUserId())
                    .active()
                    .singleResult();

            if (Objects.nonNull(applyUserTask)) {
                complete(TaskCompleteModelImpl.builder()
                        .taskId(applyUserTask.getId())
                        .taskVariables(processInstanceVars)
                        .userId(model.getUserId())
                        .candidateOrAssigned(model.getCandidateOrAssigned())
                        .build());

            }
        }

        return processInstance;
    }

    @Override
    public void complete(TaskCompleteModel model) {
        Assert.notNull(model.getTaskId(), "The taskId of the process can not be empty!");
        TaskService taskService = getProcessEngine().getTaskService();

        //扎到相关任务
        Task task = Optional.ofNullable(taskService.createTaskQuery()
                        .taskId(model.getTaskId())
                        .active()
                        .singleResult())
                .orElseThrow(() -> new WorkflowException(String.format("cannot find Task for taskId:[%s]", model.getTaskId())));

        //1.找到任务的处理人，若任务未签收（没有处理人），则抛出异常
        //2.若处理人与任务模型的用户不匹配也抛出异常
        String assignee = task.getAssignee();
        Assert.notNull(assignee, String.format("Task for taskId:[%s] did not claimed", task.getId()));
        if (!assignee.equals(model.getUserId())) {
            throw new WorkflowException(String.format("Task for taskId:[%s] can not complete by user:[%s]", task.getId(), model.getUserId()));
        }
        taskService.setVariables(task.getId(), model.getVariables());
        taskService.setVariablesLocal(task.getId(), model.getTaskVariables());
        task.getBusinessKey();
        taskService.complete(task.getId());

        //如果有设置目标任务关键字则进行任务跳转
        if (StringUtils.isNotBlank(model.getTargetTaskKey())) {
            jump(task.getId(), model.getTargetTaskKey());
        }
        //完成此环节后，检查有没下个环节，有的话且是未设置办理人或候选人的情况下，使用模型进行设置
        List<Task> taskList = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .active()
                .list();

        if (!taskList.isEmpty()) {
            //设置任务的候选人
            taskList.forEach(LambdaUtils.consumerWrapper(nextTask -> setCandidateOrAssigned(nextTask, model)));
        }

    }

    /**
     * 设置候选人或处理人
     *
     * @param task  当前的任务
     * @param model 流程人员模型
     */
    protected void setCandidateOrAssigned(Task task, ProcessPersonalModel model) {
        task.getProcessInstanceId();
        TaskService taskService = getProcessEngine().getTaskService();
        //查询当前任务是否已经有候选人或办理人
        RepositoryService repositoryService = getProcessEngine().getRepositoryService();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
        FlowElement flowElement = bpmnModel.getFlowElement(task.getTaskDefinitionKey());
        if (flowElement instanceof UserTask) {
            UserTask userTask = (UserTask) flowElement;
            List<String> taskCandidateUsersDefine = userTask.getCandidateUsers();
            //没有负责人，则用解析器解析流程任务定义的候选人或参数传入来的候选人
            if (StringUtils.isBlank(task.getAssignee())) {
                List<String> allCandidateOrAssigned = new ArrayList<>();
                List<String> modelCandidateOrAssigned = model.getCandidateOrAssigned();
                if (!CollectionUtils.isEmpty(modelCandidateOrAssigned)) {
                    allCandidateOrAssigned.addAll(modelCandidateOrAssigned);
                }

                allCandidateOrAssigned.addAll(taskCandidateUsersDefine);

                //删除任务候选人
                allCandidateOrAssigned.forEach(candidateDefine -> taskService.deleteCandidateUser(task.getId(), candidateDefine));

                RuntimeService runtimeService = getProcessEngine().getRuntimeService();

                WorkflowContextImpl workflowContext = WorkflowContextImpl.builder()
                        .processEngine(getProcessEngine())
                        .processInstance(
                                runtimeService
                                        .createProcessInstanceQuery()
                                        .processInstanceId(task.getProcessInstanceId())
                                        .singleResult()
                        )
                        .task(task)
                        .flowElement(flowElement)
                        .currentUserId(model.getUserId())
                        .build();
                //解析器解析完成后，把真正的候选人添加到任务中去
                Optional.ofNullable(getTaskCandidateResolver().resolve(workflowContext, allCandidateOrAssigned))
                        .ifPresent(resolvedCandidates -> {
                            if (resolvedCandidates.size() == 1) {
                                taskService.setAssignee(task.getId(), resolvedCandidates.get(1));
                            } else {
                                resolvedCandidates.stream().filter(Objects::nonNull)
                                        .forEach(realUserId -> taskService.addCandidateUser(task.getId(), realUserId));
                            }
                        });
            }
        }

    }

    @Override
    public void claim(String taskId, String userId) {
        TaskService taskService = getProcessEngine().getTaskService();
        Task task = Optional.ofNullable(taskService.createTaskQuery()
                        .taskId(taskId)
                        .taskCandidateOrAssigned(userId)
                        .singleResult())
                .orElseThrow(() -> new WorkflowException(String.format("can not claim Task for taskId:[%s]", taskId)));

        String assignee = task.getAssignee();
        if (StringUtils.isNotBlank(assignee)) {
            throw new WorkflowException(String.format("Task for taskId:[%s] has been claimed", taskId));
        }

        taskService.claim(taskId, userId);
    }

    @Override
    public void jump(String taskId, String targetTaskKey) {
        TaskService taskService = getProcessEngine().getTaskService();

        Task task = Optional.ofNullable(taskService.createTaskQuery().taskId(taskId).singleResult())
                .orElseThrow(() -> new WorkflowException(String.format("cannot find Task for taskId:[%s]", taskId)));

        Optional.ofNullable(getProcessEngine().getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).
                active().singleResult()).orElseThrow(() -> new WorkflowException("This ProcessInstance is not active,cannot do jump"));

        //构建跳转命令并执行
        getProcessEngine().getManagementService().executeCommand(
                JumpTaskCmd.builder()
                        .executionId(task.getExecutionId())
                        .targetTaskKey(targetTaskKey)
                        .build()
        );

    }

    @Override
    public void suspend(String instanceId) {
        getProcessEngine().getRuntimeService().suspendProcessInstanceById(instanceId);
    }

    @Override
    public void activate(String instanceId) {
        getProcessEngine().getRuntimeService().activateProcessInstanceById(instanceId);
    }

}