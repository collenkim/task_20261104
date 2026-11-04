package task.group

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication class GroupServiceApplication

fun main(args: Array<String>) {
  runApplication<GroupServiceApplication>(*args)
}
